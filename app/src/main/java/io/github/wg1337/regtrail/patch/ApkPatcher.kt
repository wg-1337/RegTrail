package io.github.wg1337.regtrail.patch

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Stages a patch reports, so a multi-second operation never looks frozen.
 *
 * The labels are user-facing Chinese on purpose: they are rendered verbatim by
 * `PatchScreen`.
 */
internal enum class PatchStage(val label: String) {
    Resolve("读取目标应用"),
    ReadManifest("解析 AndroidManifest.xml"),
    Repack("复制 APK 条目"),
    Inject("注入载荷与窗口资源"),
    RewriteManifest("改写 AndroidManifest.xml"),
    Sign("对齐并签名"),
    Verify("校验签名"),
    Done("完成"),
}

/** What one successful patch produced. */
internal data class PatchOutcome(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val outputApk: File,
    /** `classesN.dex` the payload was injected as. */
    val injectedDexEntry: String,
    val originalMinSdkVersion: Int,
    val patchedMinSdkVersion: Int,
    val originalAppComponentFactory: String?,
    val copiedEntries: Int,
    val warnings: List<String>,
    val signerSha256: String,
)

/**
 * Produces a re-signed copy of an installed app's APK that carries the RegTrail
 * overlay payload — the P6 "persistent injection" feature.
 *
 * ## What the output contains
 *
 * ```
 * AndroidManifest.xml          rewritten: appComponentFactory = payload factory,
 *                              minSdkVersion >= 28, original factory kept as meta-data
 * classes.dex … classesN.dex   the target's own dexes, untouched
 * classes<N+1>.dex             our payload dex (assets/regtrail/payload.dex, renamed)
 * assets/regtrail/{index.html,style.css,app.js}   the panel's web UI
 * everything else              copied verbatim, compression method preserved
 * ```
 *
 * ## Why the original dexes are *not* replaced
 *
 * LSPatch replaces `classes.dex` with a tiny loader and nests the original APK in
 * `assets/`, then swaps the class loader from native code and defeats signature
 * checks. None of that is available here: this payload has no root, no LSPosed and
 * no native code. Appending one extra dex instead needs nothing from the platform
 * beyond "the class loader reads every `classes*.dex`", which it has done natively
 * since API 21. The payload still runs *first* because `AppComponentFactory` is
 * instantiated before any application code.
 *
 * ## What it deliberately does not do
 *
 * - No signature-check bypass: an app that verifies its own certificate will
 *   notice the new one, and Play Integrity is a server-side verdict no client
 *   patch can influence.
 * - No split merging: only `base.apk` is patched; config splits become a warning.
 * - No `targetSdkVersion` rewriting: that changes the app's behaviour contract, so
 *   a target below the install floor is refused instead of silently altered.
 *
 * ## How this pipeline was validated
 *
 * Not only by "it compiles": the same steps were run on a desktop JVM against this
 * app's own 19.8 MB debug APK, and the resulting patched APK was checked with the
 * build-tools binaries — `apksigner verify` → *Verifies* (APK Signature Scheme v3),
 * `zipalign -c -P 16 -v 4` → *Verification successful* (native libraries on 16 KiB
 * boundaries, `resources.arsc` 4-byte aligned), and `aapt2 dump xmltree` showing
 * `android:appComponentFactory(0x0101057a)` plus `minSdkVersion=28` and the
 * original factory preserved in `<meta-data>` inside `<application>`.
 *
 * Blocking, CPU- and IO-bound: call from `Dispatchers.IO`.
 */
internal class ApkPatcher(
    private val context: Context,
    private val signingKeyStore: SigningKeyStore,
) {

    /**
     * Patches [packageName] and writes the signed APK into [outputDir].
     *
     * @param onStage called for every stage transition, on the calling thread.
     * @throws PatchException for every failure the user can act on.
     */
    fun patch(
        packageName: String,
        outputDir: File,
        onStage: (PatchStage, String?) -> Unit = { _, _ -> },
    ): PatchOutcome {
        onStage(PatchStage.Resolve, packageName)

        if (packageName == context.packageName) {
            throw PatchException("不能对 RegTrail 自身打包")
        }

        val packageManager = context.packageManager
        val appInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }
            .getOrElse { throw PatchException("无法读取目标应用信息：$packageName", it) }
        val label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: packageName
        val sourceApk = File(appInfo.sourceDir.orEmpty())
        if (!sourceApk.isFile || !sourceApk.canRead()) {
            throw PatchException("目标 APK 不可读：${appInfo.sourceDir}")
        }

        val warnings = mutableListOf<String>()
        val splits = appInfo.splitSourceDirs.orEmpty().filter { it.isNotBlank() }
        if (splits.isNotEmpty()) {
            warnings += "目标使用 split APK（${splits.size} 个分片）。补丁只包含 base.apk，" +
                "部分语言/密度资源可能缺失。"
        }
        if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
            warnings += "目标是系统应用。重新签名后通常无法覆盖安装。"
        }
        val versionCode = versionCodeOf(packageManager, packageName)

        outputDir.mkdirs()
        if (!outputDir.isDirectory) {
            throw PatchException("无法创建输出目录：$outputDir")
        }
        // Two copies exist on disk at once (unsigned + signed), so demand headroom
        // up front instead of failing halfway through a 100 MB app.
        val requiredBytes = sourceApk.length() * 2L + MIN_FREE_SPACE_BYTES
        val usableBytes = outputDir.usableSpace
        if (usableBytes in 1 until requiredBytes) {
            throw PatchException(
                "存储空间不足：需要约 ${requiredBytes / MEBIBYTE} MB，可用 ${usableBytes / MEBIBYTE} MB",
            )
        }

        val finalApk = File(outputDir, outputName(packageName, versionCode))
        val unsignedApk = File(outputDir, "${finalApk.name}.unsigned")
        finalApk.delete()
        unsignedApk.delete()

        onStage(PatchStage.ReadManifest, null)
        val payloadDex = readAsset(PAYLOAD_DEX_ASSET)
            ?: throw PatchException(
                "内置载荷缺失：assets/$PAYLOAD_DEX_ASSET。请重新构建应用" +
                    "（:payload:stagePayloadDex 会生成它）。",
            )
        val overlayAssets = OVERLAY_ASSET_NAMES.map { name ->
            val assetPath = "$OVERLAY_ASSET_DIR/$name"
            assetPath to (readAsset(assetPath)
                ?: throw PatchException("内置窗口资源缺失：assets/$assetPath"))
        }

        val prepared = try {
            repack(packageName, sourceApk, unsignedApk, payloadDex, overlayAssets, warnings, onStage)
        } catch (t: Throwable) {
            // Never leave a half-written APK behind: the UI lists whatever is in
            // the output directory, and a broken file there looks installable.
            unsignedApk.delete()
            finalApk.delete()
            throw t
        }

        try {
            onStage(PatchStage.Sign, "v2+v3 · minSdk ${ManifestPatcher.REQUIRED_MIN_SDK}")
            val identity = signingKeyStore.loadOrCreate()
            ApkSigning.sign(unsignedApk, finalApk, identity)

            onStage(PatchStage.Verify, finalApk.name)
            ApkSigning.verify(finalApk)

            val patchedFacts = readManifestFacts(finalApk)
            if (patchedFacts.minSdkVersion < ManifestPatcher.REQUIRED_MIN_SDK ||
                patchedFacts.appComponentFactory != ManifestPatcher.FACTORY_CLASS
            ) {
                throw PatchException(
                    "生成后的 APK 清单不符合预期（minSdk=${patchedFacts.minSdkVersion}, " +
                        "factory=${patchedFacts.appComponentFactory}），已丢弃",
                )
            }

            onStage(PatchStage.Done, finalApk.absolutePath)
            Log.i(TAG, "patched $packageName -> ${finalApk.absolutePath} (${finalApk.length()}B)")

            return PatchOutcome(
                packageName = packageName,
                label = label,
                versionCode = versionCode,
                outputApk = finalApk,
                injectedDexEntry = prepared.dexEntryName,
                originalMinSdkVersion = prepared.facts.minSdkVersion,
                patchedMinSdkVersion = patchedFacts.minSdkVersion,
                originalAppComponentFactory = prepared.facts.appComponentFactory,
                copiedEntries = prepared.copiedEntries,
                warnings = warnings.distinct(),
                signerSha256 = identity.certificateSha256,
            )
        } catch (t: Throwable) {
            finalApk.delete()
            throw t
        } finally {
            unsignedApk.delete()
        }
    }

    // -----------------------------------------------------------------------
    // Repack + manifest rewrite
    // -----------------------------------------------------------------------

    /** Everything [repack] learned, carried out of the `ZipFile` block. */
    private class Prepared(
        val facts: TargetManifestFacts,
        val dexEntryName: String,
        val copiedEntries: Int,
    )

    /**
     * Writes the unsigned APK: rewritten manifest, every original entry (minus
     * signatures), the payload dex and the panel assets.
     */
    private fun repack(
        expectedPackage: String,
        sourceApk: File,
        unsignedApk: File,
        payloadDex: ByteArray,
        overlayAssets: List<Pair<String, ByteArray>>,
        warnings: MutableList<String>,
        onStage: (PatchStage, String?) -> Unit,
    ): Prepared {
        val overlayEntryNames = overlayAssets.mapTo(HashSet()) { (path, _) -> path }

        ZipFile(sourceApk).use { source ->
            val manifestEntry = source.getEntry(MANIFEST_ENTRY)
                ?: throw PatchException("目标 APK 缺少 AndroidManifest.xml")
            val originalManifest = source.getInputStream(manifestEntry).use { it.readBytes() }

            val facts = ManifestPatcher.read(originalManifest)
            // The UI hands us a package name; the APK on disk is the authority. A
            // mismatch means the PackageManager path and the file disagree, and
            // patching the wrong app would be a very bad outcome.
            if (facts.packageName != null && facts.packageName != expectedPackage) {
                throw PatchException(
                    "APK 清单包名是 ${facts.packageName}，与请求的 $expectedPackage 不一致，已中止",
                )
            }
            if (facts.targetSdkVersion < ManifestPatcher.MIN_INSTALLABLE_TARGET_SDK) {
                throw PatchException(
                    "targetSdkVersion=${facts.targetSdkVersion} 低于 Android 15 的安装下限" +
                        "（${ManifestPatcher.MIN_INSTALLABLE_TARGET_SDK}），系统会直接拒绝安装。" +
                        "提高它又会改变目标应用的行为，因此不自动修改。",
                )
            }
            if (!facts.appComponentFactory.isNullOrBlank()) {
                warnings += "目标已声明 AppComponentFactory（${facts.appComponentFactory}），" +
                    "本补丁会替换它；原值已记录在 <meta-data> 中。"
            }

            onStage(PatchStage.RewriteManifest, ManifestPatcher.FACTORY_CLASS)
            val patchedManifest = ManifestPatcher.patch(originalManifest, facts.appComponentFactory)

            val dexEntryName = nextDexEntryName(source)
            Log.i(
                TAG,
                "repacking ${sourceApk.name}: ${sourceApk.length()}B " +
                    "minSdk=${facts.minSdkVersion} targetSdk=${facts.targetSdkVersion} " +
                    "payloadEntry=$dexEntryName",
            )

            onStage(PatchStage.Repack, "${source.size()} 个条目")
            var copiedEntries = 0
            ZipOutputStream(bufferedOutput(unsignedApk)).use { output ->
                // The manifest goes first by convention; apksig and the platform
                // tolerate any order, but tools (and `unzip -l`) expect it first.
                writeBytes(output, MANIFEST_ENTRY, patchedManifest)

                for (entry in source.entries()) {
                    val name = entry.name
                    if (name == MANIFEST_ENTRY) continue
                    if (isSignatureFile(name)) continue
                    if (name in overlayEntryNames) {
                        // A target that already ships assets/regtrail/ would
                        // otherwise produce duplicate zip entries.
                        warnings += "目标已包含 $name，已用本应用的资源覆盖"
                        continue
                    }
                    copyEntry(source, entry, output)
                    copiedEntries++
                }

                onStage(PatchStage.Inject, dexEntryName)
                writeBytes(output, dexEntryName, payloadDex)
                for ((assetPath, bytes) in overlayAssets) {
                    writeBytes(output, assetPath, bytes)
                }
            }

            return Prepared(facts, dexEntryName, copiedEntries)
        }
    }

    // -----------------------------------------------------------------------
    // Zip plumbing
    // -----------------------------------------------------------------------

    /**
     * Copies one entry, preserving its compression method.
     *
     * Compression is *not* normalised to deflate on purpose: native libraries and
     * `resources.arsc` must stay uncompressed for `extractNativeLibs=false` and for
     * mmap-based resource loading, and re-storing a deflated library would change
     * how the app loads native code.
     *
     * No alignment work happens here either — apksig re-aligns stored entries to 4
     * bytes and native libraries to 16 KiB while signing (see [ApkSigning]).
     */
    private fun copyEntry(source: ZipFile, entry: ZipEntry, output: ZipOutputStream) {
        val target = ZipEntry(entry.name)
        if (entry.time > 0L) {
            target.time = entry.time
        }

        if (entry.method == ZipEntry.STORED || entry.name == RESOURCES_ARSC) {
            val size = entry.size
            if (size < 0L) {
                throw PatchException("条目 ${entry.name} 缺少大小信息，无法原样复制")
            }
            target.method = ZipEntry.STORED
            target.size = size
            target.compressedSize = size
            target.crc = entry.crc
        } else {
            target.method = ZipEntry.DEFLATED
        }

        output.putNextEntry(target)
        source.getInputStream(entry).use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
        output.closeEntry()
    }

    /** Writes an injected entry. Deflated: it is read through AssetManager, never mmapped. */
    private fun writeBytes(output: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        // Fixed timestamp: two patches of the same input then differ only by the
        // signature, which makes "did anything else change?" answerable.
        entry.time = INJECTED_ENTRY_TIMESTAMP
        entry.method = ZipEntry.DEFLATED
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** `classes.dex` counts as 1; returns `classes<n+1>.dex` for the highest n. */
    private fun nextDexEntryName(source: ZipFile): String {
        var highest = 0
        for (entry in source.entries()) {
            val name = entry.name
            if (!name.startsWith(DEX_PREFIX) || !name.endsWith(DEX_SUFFIX)) continue
            val middle = name.substring(DEX_PREFIX.length, name.length - DEX_SUFFIX.length)
            val index = if (middle.isEmpty()) 1 else middle.toIntOrNull() ?: continue
            if (index > highest) highest = index
        }
        return "$DEX_PREFIX${highest + 1}$DEX_SUFFIX"
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC") ||
            upper == "META-INF/MANIFEST.MF"
    }

    private fun readAsset(path: String): ByteArray? = runCatching {
        context.assets.open(path).use { it.readBytes() }
    }.getOrNull()

    private fun readManifestFacts(apk: File): TargetManifestFacts =
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY)
                ?: throw PatchException("$apk 缺少 AndroidManifest.xml")
            ManifestPatcher.read(zip.getInputStream(entry).use { it.readBytes() })
        }

    private fun versionCodeOf(packageManager: PackageManager, packageName: String): Long {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull() ?: return 0L

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun outputName(packageName: String, versionCode: Long): String =
        "$packageName-$versionCode$OUTPUT_SUFFIX"

    private fun bufferedOutput(file: File): OutputStream =
        java.io.BufferedOutputStream(file.outputStream(), COPY_BUFFER_BYTES)

    private companion object {
        const val TAG = "RegTrailPatch"

        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        const val RESOURCES_ARSC = "resources.arsc"
        const val DEX_PREFIX = "classes"
        const val DEX_SUFFIX = ".dex"

        /** Payload assets live in our own APK under this directory. */
        const val OVERLAY_ASSET_DIR = "regtrail"
        const val PAYLOAD_DEX_ASSET = "$OVERLAY_ASSET_DIR/payload.dex"
        val OVERLAY_ASSET_NAMES = listOf("index.html", "style.css", "app.js")

        const val OUTPUT_SUFFIX = "-regtrail.apk"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MEBIBYTE = 1024 * 1024
        const val MIN_FREE_SPACE_BYTES = 8L * MEBIBYTE

        /** 2020-09-13T12:26:40Z — an arbitrary fixed instant, see [writeBytes]. */
        const val INJECTED_ENTRY_TIMESTAMP = 1_600_000_000_000L
    }
}
