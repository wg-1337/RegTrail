package io.github.wg1337.regtrail.config

import android.util.Base64
import com.topjohnwu.superuser.Shell
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import io.github.wg1337.regtrail.shared.bridge.SharedPaths
import io.github.wg1337.regtrail.shared.model.ModuleConfigSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes the configuration snapshot for injected modules to read.
 *
 * ## Why root and a plain file
 * The module runs inside a *third-party* app's process. That app cannot read
 * our private storage, and we hold no Binder handle into it. What both sides
 * reliably have is a root shell, so the settings app stages the snapshot at
 * [SharedPaths.CONFIG_CACHE] (`/data/local/tmp/regtrail/config.json`) with
 * world-readable permissions and the module simply reads it.
 *
 * This keeps the data path independent of `RemotePreferences` (which needs the
 * framework service and only lives while the settings app does), and means an
 * exported no-root build can reuse the very same file.
 *
 * ## Encoding
 * The JSON is base64-encoded and decoded on-device. The payload therefore never
 * passes through shell parsing, which removes every quoting/escaping hazard.
 * Two decoders are attempted (`base64 -d`, then `toybox base64 -d`) because
 * applet availability differs across ROMs; the file is verified afterwards.
 */
class ConfigPublisher(
    /** Needed only to locate our own APK so it can be staged for the module. */
    private val appContext: android.content.Context? = null,
) {

    /**
     * Writes [snapshot] and verifies it landed.
     *
     * @return [Result.success] when the file was written and read back intact.
     */
    suspend fun publish(snapshot: ModuleConfigSnapshot): Result<Unit> =
        withContext(Dispatchers.IO) {
            val json = runCatching {
                BridgeApi.json.encodeToString(ModuleConfigSnapshot.serializer(), snapshot)
            }.getOrElse { return@withContext Result.failure(it) }

            val shell = obtainRootShell()
                ?: return@withContext Result.failure(
                    ConfigException("无法获取 Root shell，请确认已授予 Root 权限"),
                )

            val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val tmp = "${SharedPaths.CONFIG_CACHE}.tmp"

            val script = listOf(
                "mkdir -p ${SharedPaths.BASE_DIR} ${SharedPaths.CMD_DIR} ${SharedPaths.OUT_DIR}",
                "chmod 755 ${SharedPaths.BASE_DIR}",
                writeCommand(b64, tmp),
                "chmod 644 $tmp",
                // Replace atomically so a module never observes a partial file.
                "mv -f $tmp ${SharedPaths.CONFIG_CACHE}",
                "chmod 644 ${SharedPaths.CONFIG_CACHE}",
            ) + stageModuleApkCommands()

            val result = runCatching {
                shell.newJob().add(*script.toTypedArray()).exec()
            }.getOrElse { return@withContext Result.failure(it) }

            if (!result.isSuccess) {
                return@withContext Result.failure(
                    ConfigException(
                        "写入配置失败: ${result.err.joinToString("; ").take(300)}",
                    ),
                )
            }

            // Verify by reading it back rather than trusting the exit code.
            readBack().exceptionOrNull()?.let { cause ->
                return@withContext Result.failure(
                    ConfigException(
                        "配置已写入但回读失败: ${cause.message ?: "未知原因"}",
                    ),
                )
            }
            Result.success(Unit)
        }

    /**
     * Stages our own APK where the injected module can always read it.
     *
     * ## Why this exists
     * The module needs to serve its web UI out of its own APK. The documented
     * route is `XposedInterface.getModuleApplicationInfo().sourceDir`, but on the
     * APatch + LSPosed 2.2.1 test device that path did not yield a readable APK,
     * so `assets/regtrail/index.html` could not be read and the overlay came up
     * completely invisible.
     *
     * Copying the APK to a world-readable location removes the dependency on
     * framework internals entirely: the module simply opens this file. It costs
     * 19 MB of `/data/local/tmp`, which is acceptable for a debugging tool and is
     * overwritten in place rather than accumulating.
     */
    private fun stageModuleApkCommands(): List<String> {
        val source = runCatching { appContext?.applicationInfo?.sourceDir }.getOrNull()
        if (source.isNullOrBlank()) return emptyList()

        val staged = "${SharedPaths.BASE_DIR}/module_apk"

        // `$` starts a Kotlin template, so shell `$(...)` substitutions must be
        // written as ${'$'}(...). Building the string with an explicit dollar
        // variable keeps the resulting shell line readable.
        val d = '$'
        val cmd = buildString {
            // Copy only when the staged size differs from the source, so a
            // 19 MB rewrite does not happen on every config publish.
            append("if [ ! -f ").append(staged).append(" ] || [ \"")
            append(d).append("(stat -c %s ").append(source)
            append(" 2>/dev/null)\" != \"").append(d).append("(stat -c %s ")
            append(staged).append(" 2>/dev/null)\" ]; then cp -f ")
            append(source).append(' ').append(staged)
            append(" && chmod 644 ").append(staged).append("; fi")
        }
        return listOf(cmd)
    }

    /**
     * Reads the staged snapshot back, for the "what does the module actually
     * see?" diagnostic in the UI.
     *
     * ## Read directly, not through the shell
     * The file is staged as `0644` under `/data/local/tmp` (mode `0711`), so the
     * app can read it with plain Java I/O — verified on the test device. An
     * earlier version read it back through the root shell with
     * `(base64 FILE || toybox base64 FILE)` and got **empty output with exit 0**
     * even though the same command works from an adb root shell, which made a
     * correct write look like a failed publish and blocked the whole feature.
     *
     * Reading it directly removes the shell from the verification path entirely:
     * fewer moving parts, and any failure is now a real filesystem error with a
     * real errno instead of a silent empty result.
     */
    suspend fun readBack(): Result<ModuleConfigSnapshot> = withContext(Dispatchers.IO) {
        val file = java.io.File(SharedPaths.CONFIG_CACHE)

        when {
            !file.exists() -> return@withContext Result.failure(
                ConfigException("配置文件不存在（${file.absolutePath}）"),
            )
            file.length() == 0L -> return@withContext Result.failure(
                ConfigException("配置文件为空（${file.absolutePath}）"),
            )
        }

        val text = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse {
                return@withContext Result.failure(
                    ConfigException("读取配置失败：${it.message ?: "未知原因"}"),
                )
            }

        runCatching {
            BridgeApi.json.decodeFromString(ModuleConfigSnapshot.serializer(), text)
        }.recoverCatching { cause ->
            throw ConfigException(
                "配置解析失败：${cause.message ?: "未知原因"}；前 120 字符：${text.take(120)}",
            )
        }
    }

    /** True when a root shell can be obtained at all. */
    suspend fun canUseRoot(): Boolean = withContext(Dispatchers.IO) {
        obtainRootShell() != null
    }

    /**
     * Returns a shell that is definitely root, building a fresh one if needed.
     *
     * ## Why this is not just `Shell.getShell()`
     * libsu caches the shell it creates. If the very first request happens before
     * the user has answered the su manager's prompt (which is exactly what
     * happens when the terminal is opened during app startup), that first shell
     * comes back **alive but without root**, and every later `getShell()` returns
     * the same unprivileged instance.
     *
     * Measured on an APatch 1.12.2 device:
     * ```
     * isAppGrantedRoot=false, shell=alive=true, isRoot=false, status=0
     * ```
     * Note `status=0` — libsu's own ROOT_SHELL constant. So the shell claims to
     * be a root shell while `isRoot()` is false, and trusting the cache means the
     * app never writes its config even after the user grants root.
     *
     * The fix is to treat a non-root cached shell as a miss: close it and build a
     * new one, which re-issues the su request.
     */
    private fun obtainRootShell(): Shell? {
        val cached = runCatching { Shell.getShell() }.getOrNull()
        if (cached != null && cached.isRoot && cached.isAlive) return cached

        // The cached shell is either missing, dead, or unprivileged. Drop it and
        // ask for a real root shell.
        runCatching { cached?.close() }

        val rebuilt = runCatching {
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(20)
                .build()
        }.getOrNull()

        return rebuilt?.takeIf { it.isRoot && it.isAlive }
    }

    /**
     * Explains *why* a root shell is unavailable.
     *
     * "无法获取 Root shell" covers three different situations with three
     * different fixes, and the user needs to know which one they are in:
     *  - the su request was denied or is still pending,
     *  - the shell came back alive but without root,
     *  - the shell died.
     *
     * `Shell.isAppGrantedRoot()` distinguishes "never granted" from "granted but
     * not root" using the manager's own policy database, which is far more
     * informative than probing.
     */
    /**
     * Requests a root shell for the UI's "grant root" button.
     *
     * Exposed so the user can trigger the manager's prompt on demand instead of
     * relying on a startup-time request they may never see.
     */
    suspend fun obtainRootShellForUi(): Shell? = withContext(Dispatchers.IO) {
        obtainRootShell()
    }

    /**
     * Explains *why* a root shell is unavailable, and whether APatch has already
     * granted it.
     *
     * The three cases need different advice, and one of them is a real trap:
     *
     *  1. **Not granted** — the su prompt was declined or never shown.
     *  2. **Granted in APatch's own policy but not effective in this process** —
     *     APatch writes grants to `/data/adb/ap/package_config`, which is only
     *     read when a process asks for su. If the app cached a non-root shell
     *     before the grant landed, the fix is simply to restart the app.
     *  3. **libsu cannot tell** — `Shell.isAppGrantedRoot()` inspects Magisk's
     *     `magisk.db`, which does not exist on APatch or KernelSU, so it always
     *     reports false there. That is a libsu limitation, not a denial.
     */
    suspend fun rootDiagnosis(): String = withContext(Dispatchers.IO) {
        val granted = runCatching { Shell.isAppGrantedRoot() }.getOrNull()
        val cached = runCatching { Shell.getShell() }.getOrNull()
        val fresh = obtainRootShell()
        val apatchAllow = apatchAllowsCurrentUid()

        buildString {
            append("libsu isAppGrantedRoot=")
            append(
                when (granted) {
                    true -> "true"
                    false -> "false"
                    null -> "null"
                },
            )
            if (granted == false) {
                append("（注意：该接口只读 Magisk 的 magisk.db，APatch/KernelSU 上恒为 false）")
            }

            append(" | cached shell: ")
            if (cached == null) {
                append("null")
            } else {
                append("alive=${cached.isAlive}, isRoot=${cached.isRoot}, status=${cached.status}")
            }

            append(" | fresh root shell: ")
            append(fresh != null)

            append(" | APatch package_config allows this uid: ")
            append(
                when (apatchAllow) {
                    true -> "YES — 已授权，重启应用即可生效"
                    false -> "NO — 请在 APatch 管理器中允许本应用"
                    null -> "n/a（未检测到 APatch 策略文件）"
                },
            )
        }
    }

    /**
     * Reads APatch's grant list for this app's uid.
     *
     * Format (measured on APatch 1.12.2), one row per app:
     * ```
     * pkg,exclude,allow,uid,to_uid,sctx
     * bin.mt.plus.canary,0,1,10165,0,u:r:magisk:s0
     * ```
     * Column 1 is the package, column 2 the `allow` flag, column 3 the uid.
     * The file is `0600 root`, so this needs a root shell — which, by definition,
     * we do not have when the answer is "not granted". It is therefore only
     * informative once *some* root path works, and it returns null otherwise.
     */
    private suspend fun apatchAllowsCurrentUid(): Boolean? {
        val uid = android.os.Process.myUid()
        val shell = runCatching { Shell.getShell() }.getOrNull()
        if (shell == null || !shell.isRoot) return null

        val result = runCatching {
            shell.newJob().add("cat /data/adb/ap/package_config 2>/dev/null").exec()
        }.getOrNull() ?: return null
        if (!result.isSuccess || result.out.isEmpty()) return null

        return result.out.any { line ->
            val parts = line.split(',')
            parts.size >= 4 && parts[3].trim() == uid.toString() && parts[2].trim() == "1"
        }
    }

    /**
     * Emits a single shell command that decodes [b64] into [target].
     *
     * The `base64` applet is not guaranteed to exist on every ROM (it is a
     * relatively recent toybox addition), so this emits a chain that tries the
     * plain name, then the explicit `toybox base64`, and finally fails loudly
     * instead of silently producing an empty file.
     */
    private fun writeCommand(b64: String, target: String): String = buildString {
        append("printf '%s' '").append(b64).append("' > ").append(target).append(".b64 && { ")
        append("base64 -d ").append(target).append(".b64 > ").append(target)
        append(" 2>/dev/null || toybox base64 -d ").append(target).append(".b64 > ").append(target)
        append(" 2>/dev/null || { echo 'no base64 decoder' >&2; rm -f ").append(target)
        append(".b64; false; }; } && rm -f ").append(target).append(".b64")
    }
}

class ConfigException(message: String) : Exception(message)
