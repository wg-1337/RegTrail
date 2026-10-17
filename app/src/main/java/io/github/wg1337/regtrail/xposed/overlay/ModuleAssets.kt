package io.github.wg1337.regtrail.xposed.overlay

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.wg1337.regtrail.shared.bridge.SharedPaths
import io.github.wg1337.regtrail.xposed.RegTrailLog
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads the floating window's web UI out of the module's own APK.
 *
 * ## Why the naive lookup fails
 * The module's classes are loaded by the framework from an **in-memory dex**
 * (`ByteBufferDexClassLoader` over shared memory), so there is no `Context` that
 * owns our APK and the module classloader has no APK path attached.
 *
 * `XposedInterface.getModuleApplicationInfo()` is the sanctioned route to the
 * module's `ApplicationInfo`, but on the APatch + LSPosed 2.2.1 test device its
 * `sourceDir` did **not** resolve to a readable APK — the window's HTML failed to
 * load with "asset not readable from module APK", which presented as a completely
 * invisible overlay.
 *
 * So the path is resolved through a chain and every step is logged, because
 * "silently return null" here costs a full reboot per debugging round (the module
 * APK only reloads when the framework restarts).
 */
internal class ModuleAssets(
    private val module: XposedModule,
    /** The *target* app's context, used only to reach PackageManager. */
    private val hostContext: Context? = null,
) {

    private val cache = HashMap<String, String>()

    @Volatile
    private var apkPath: String? = null

    @Volatile
    private var resolved = false

    /** @return the UTF-8 text of `assets/regtrail/[path]`, or null when unavailable. */
    fun readText(path: String): String? {
        cache[path]?.let { return it }
        val text = readFromApk(path) ?: readFromMirror(path) ?: return null
        synchronized(cache) { cache[path] = text }
        return text
    }

    private fun readFromApk(path: String): String? {
        val apk = resolveApkPath() ?: run {
            RegTrailLog.w("ModuleAssets: could not resolve the module APK path")
            return null
        }
        // `path` is already relative to the APK's asset root, e.g.
        // "regtrail/index.html". Do NOT prepend the asset directory here — doing
        // so produced "assets/regtrail/regtrail/index.html", which silently failed
        // and rendered an invisible overlay.
        val entryName = "assets/$path"

        return try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(entryName)
                if (entry == null) {
                    // Log a few nearby entries once, so a naming mismatch is
                    // obvious instead of guessed at.
                    val sample = zip.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith("assets/") }
                        .take(8)
                        .toList()
                    RegTrailLog.w("ModuleAssets: '$entryName' missing in $apk; assets sample=$sample")
                    return null
                }
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .also { RegTrailLog.d("ModuleAssets: read $entryName (${it.length} chars)") }
            }
        } catch (t: Throwable) {
            RegTrailLog.w("ModuleAssets: failed reading $entryName from $apk", t)
            null
        }
    }

    /** Last-resort fallback: a copy the settings app may have staged for us. */
    private fun readFromMirror(path: String): String? = try {
        val f = File(File(SharedPaths.BASE_DIR, "ui"), path)
        if (f.isFile) {
            RegTrailLog.i("ModuleAssets: using staged mirror for $path")
            f.readText(Charsets.UTF_8)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Resolves the module APK path, trying in order:
     *
     *  1. `XposedInterface.getModuleApplicationInfo().sourceDir` — the documented
     *     route; the module's own ApplicationInfo.
     *  2. The host process's PackageManager queried by our package name. The
     *     target app normally cannot see our package, so this often fails, but it
     *     costs nothing to try.
     *  3. A path the settings app staged at `/data/local/tmp/regtrail/module_apk`.
     *
     * Each attempt is logged exactly once so a failure is diagnosable from a
     * single logcat capture.
     */
    private fun resolveApkPath(): String? {
        apkPath?.let { return it }
        if (resolved) return apkPath
        resolved = true

        // 1) framework-provided ApplicationInfo
        runCatching {
            val info = module.moduleApplicationInfo
            val dir = info?.sourceDir
            val readable = dir != null && File(dir).canRead() && File(dir).isFile
            RegTrailLog.i(
                "ModuleAssets: getModuleApplicationInfo sourceDir=$dir readable=$readable",
            )
            if (readable) {
                apkPath = dir
                return dir
            }
        }.onFailure { RegTrailLog.w("ModuleAssets: getModuleApplicationInfo failed", it) }

        // 2) host PackageManager
        runCatching {
            val pm = hostContext?.packageManager
            if (pm != null) {
                val info = pm.getApplicationInfo("io.github.wg1337.RegTrail", 0)
                RegTrailLog.i("ModuleAssets: host PM sourceDir=${info.sourceDir}")
                if (File(info.sourceDir).canRead()) {
                    apkPath = info.sourceDir
                    return info.sourceDir
                }
            }
        }.onFailure { RegTrailLog.w("ModuleAssets: host PM lookup failed", it) }

        // 3) staged copy published by the settings app
        runCatching {
            val staged = File(SharedPaths.BASE_DIR, "module_apk")
            if (staged.isFile && staged.canRead()) {
                RegTrailLog.i("ModuleAssets: using staged module APK ${staged.absolutePath}")
                apkPath = staged.absolutePath
                return staged.absolutePath
            }
        }

        return null
    }

    private companion object {
        /**
         * Name of the staged copy the settings app maintains under
         * [SharedPaths.BASE_DIR], used when the framework-provided path is not
         * readable.
         */
        const val STAGED_APK_NAME = "module_apk"
    }
}
