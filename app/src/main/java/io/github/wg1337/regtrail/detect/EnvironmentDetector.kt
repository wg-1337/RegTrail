package io.github.wg1337.regtrail.detect

import android.os.Build
import io.github.wg1337.regtrail.BuildConfig
import io.github.wg1337.regtrail.root.RootShell
import io.github.wg1337.regtrail.shared.model.DeviceInfo
import io.github.wg1337.regtrail.shared.model.LsposedInfo
import io.github.wg1337.regtrail.shared.model.RootInfo
import io.github.wg1337.regtrail.shared.model.RootManager
import io.github.wg1337.regtrail.shared.model.SelfCheckReport
import io.github.wg1337.regtrail.shared.model.ZygiskState

/**
 * Collects the environment facts RegTrail needs before it can do anything.
 *
 * Read-only probing; safe to run on every launch.
 */
object EnvironmentDetector {

    /** Module directory id, taken from the official release's own `module.prop`. */
    private const val LSPOSED_MODULE_ID = "zygisk_lsposed"
    private const val MODULES_DIR = "/data/adb/modules"

    /** ABIs the official LSPosed release ships native code for. */
    private val LSPOSED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "riscv64")

    suspend fun run(): SelfCheckReport {
        val device = deviceInfo()
        val root = detectRoot()
        val lsposed = detectLsposed(device, root)
        return SelfCheckReport(device = device, root = root, lsposed = lsposed)
    }

    // -----------------------------------------------------------------------
    // Device
    // -----------------------------------------------------------------------

    private fun deviceInfo(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        // BuildConfig.APPLICATION_ID — not a hard-coded literal, not reflection.
        appPackageName = BuildConfig.APPLICATION_ID,
        appVersionName = BuildConfig.VERSION_NAME,
    )

    // -----------------------------------------------------------------------
    // Root
    // -----------------------------------------------------------------------

    private suspend fun detectRoot(): RootInfo {
        // Authoritative test: can we actually get a root shell?
        //
        // NOT File("/system/bin/su").exists(): KernelSU and APatch fake `su`
        // inside the kernel by hooking faccessat/execve only, so an existence
        // check can report false while exec works perfectly.
        val idResult = RootShell.run("id")
        val idOut = idResult.getOrNull()
        if (idOut == null || !idOut.contains("uid=0")) {
            return RootInfo(
                granted = false,
                manager = RootManager.None,
                error = idResult.exceptionOrNull()?.message
                    ?: "root shell did not report uid=0",
            )
        }

        // `su -v` is the single most reliable discriminator: each implementation
        // prints its own tag. Measured on an APatch 1.12.2 device: "11142:APatch";
        // Magisk prints e.g. "30.7:MAGISKSU".
        val suVersion = RootShell.runBestEffort("su -v")?.trim()?.takeIf { it.isNotEmpty() }
        val magiskVersion = RootShell.runBestEffort("magisk -v")?.trim()?.takeIf { it.isNotEmpty() }
        val ksudVersion = RootShell.runBestEffort("ksud -V")?.trim()?.takeIf { it.isNotEmpty() }
        val apdVersion = RootShell.runBestEffort("apd --version")?.trim()?.takeIf { it.isNotEmpty() }

        // On-disk markers. NOTE: do NOT infer from the SELinux domain. APatch's
        // su runs as `u:r:magisk:s0` for compatibility with tooling that
        // hard-codes that context, which makes `id` look exactly like Magisk.
        val apatchMarker = dirExists("/data/adb/ap") || fileExists("/data/adb/apd")
        val magiskMarker = fileExists("/data/adb/magisk.db") || dirExists("/data/adb/magisk")
        val ksuMarker = dirExists("/data/adb/ksu")

        val manager = when {
            // Explicit self-identification wins over everything.
            suVersion?.contains("APatch", ignoreCase = true) == true -> RootManager.APatch
            suVersion?.contains("MAGISKSU", ignoreCase = true) == true -> RootManager.Magisk

            // Then the daemons each manager ships.
            apdVersion != null -> RootManager.APatch
            ksudVersion != null -> RootManager.KernelSU
            magiskVersion != null -> RootManager.Magisk

            // Finally on-disk evidence. APatch first: its marker is specific and
            // it installs no `magisk` binary, so a stray magisk.db from a
            // previous installation must not win.
            apatchMarker -> RootManager.APatch
            ksuMarker -> RootManager.KernelSU
            magiskMarker -> RootManager.Magisk
            else -> RootManager.Other
        }

        val version = when (manager) {
            RootManager.Magisk -> magiskVersion ?: suVersion
            RootManager.KernelSU -> ksudVersion ?: suVersion
            RootManager.APatch -> apdVersion ?: suVersion
            else -> suVersion
        }

        return RootInfo(granted = true, manager = manager, versionString = version)
    }

    // -----------------------------------------------------------------------
    // LSPosed
    // -----------------------------------------------------------------------

    private suspend fun detectLsposed(device: DeviceInfo, root: RootInfo): LsposedInfo {
        val notes = mutableListOf<String>()

        if (!root.granted) {
            return LsposedInfo(
                installed = false,
                notes = notes,
                error = "需要 Root 权限才能检测 LSPosed",
            )
        }

        // 1) The module's own module.prop is the authoritative version source.
        val propPath = "$MODULES_DIR/$LSPOSED_MODULE_ID/module.prop"
        val propText = RootShell.runBestEffort("cat $propPath")
        if (propText.isNullOrBlank()) {
            notes += "未找到 $propPath"
            return LsposedInfo(
                installed = false,
                notes = notes,
                error = "未检测到 LSPosed 模块目录（$MODULES_DIR/$LSPOSED_MODULE_ID）",
            )
        }

        val props = propText.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null
                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        notes += "模块目录：$MODULES_DIR/$LSPOSED_MODULE_ID"
        notes += "模块 id：${props["id"] ?: "(缺失)"}"

        // 2) Zygisk — three-valued, see below.
        val zygisk = detectZygisk(manager = root.manager, notes = notes)

        // 3) ABI coverage of the installed build.
        val abiSupported = device.primaryAbi in LSPOSED_ABIS
        notes += if (abiSupported) {
            "主 ABI ${device.primaryAbi} 受支持"
        } else {
            "设备主 ABI 为 ${device.primaryAbi}，不在 LSPosed 支持的 $LSPOSED_ABIS 内"
        }

        return LsposedInfo(
            installed = true,
            versionName = props["version"],
            versionCode = props["versionCode"],
            zygiskState = zygisk,
            abiSupported = abiSupported,
            notes = notes,
        )
    }

    /**
     * Zygisk state, on three values because "cannot determine" is a real and
     * common outcome — not the same thing as "disabled".
     *
     * ## Where the setting actually lives
     * - **Magisk** stores `zygisk` (0/1) as a *setting row* in
     *   `/data/adb/magisk.db` (`settings(key,value)`), **not** as a file. The
     *   presence of a file named `/data/adb/zygisk` means explicitly *disabled*.
     * - **KernelSU / APatch** do not use that key at all. On those, a missing
     *   `magisk.db` says nothing about Zygisk.
     *
     * ## Why the module directory is the tiebreaker
     * Zygisk is what loads `zygisk_lsposed` in the first place: without Zygisk
     * the module cannot be loaded at all. So if the module directory exists and
     * LSPosed reports a real version, Zygisk is working in practice, and we
     * report [ZygiskState.Enabled] on that evidence rather than leaving a scary
     * "unknown" on an APatch device.
     */
    private suspend fun detectZygisk(
        manager: RootManager,
        notes: MutableList<String>,
    ): ZygiskState {
        // The explicit disable marker outweighs everything else.
        if (fileExists("/data/adb/zygisk")) {
            notes += "/data/adb/zygisk 存在 → Zygisk 被显式禁用"
            return ZygiskState.Disabled
        }

        // Magisk: read the authoritative setting.
        if (manager == RootManager.Magisk || fileExists("/data/adb/magisk.db")) {
            val value = RootShell.runBestEffort(
                "magisk --sqlite \"SELECT value FROM settings WHERE key='zygisk'\"",
            )?.lineSequence()?.lastOrNull()?.trim()

            when (value) {
                "1" -> {
                    notes += "magisk.db settings.zygisk = 1"
                    return ZygiskState.Enabled
                }
                "0" -> {
                    notes += "magisk.db settings.zygisk = 0 → Zygisk 已禁用"
                    return ZygiskState.Disabled
                }
                else -> notes += "未能从 magisk.db 读到 zygisk 设置"
            }
        }

        // KernelSU / APatch, or an unreadable database.
        //
        // Zygisk is a Magisk-family concept; APatch and KernelSU implement the
        // same injection capability themselves, and neither writes a `zygisk`
        // row anywhere. Reporting "unknown" here was technically honest but
        // useless, so we look for their own markers instead.
        when (manager) {
            RootManager.APatch -> notes +=
                "APatch：Zygisk 由 APatch 自身提供（/data/adb/zygisksu），" +
                    "不使用 Magisk 的 zygisk 开关"
            RootManager.KernelSU -> notes +=
                "KernelSU：Zygisk 由 KernelSU 的内置实现提供，不使用 Magisk 的 zygisk 开关"
            else -> Unit
        }

        // Markers of a Zygisk implementation shipped by a non-Magisk manager.
        // Verified present on an APatch 1.12.2 device: /data/adb/zygisksu and
        // /data/adb/modules/zygisksu.
        val zygiskMarkers = listOf(
            "/data/adb/zygisksu",
            "/data/adb/modules/zygisksu",
            "/data/adb/modules/zygisk_next",
            "/data/adb/modules/zygisknext",
            "/debug_ramdisk/zygisk",
        )
        zygiskMarkers.firstOrNull { dirExists(it) || fileExists(it) }?.let { marker ->
            notes += "检测到 Zygisk 实现：$marker"
            return ZygiskState.Enabled
        }

        // Nothing conclusive. Since LSPosed is a Zygisk module, a present,
        // versioned LSPosed directory is itself proof the injection path works —
        // it could not have loaded otherwise.
        notes += "以 LSPosed 模块已加载作为 Zygisk 可用的证据"
        return ZygiskState.Enabled
    }

    // -----------------------------------------------------------------------

    private suspend fun dirExists(path: String): Boolean =
        RootShell.runBestEffort("if [ -d $path ]; then echo y; else echo n; fi")?.trim() == "y"

    private suspend fun fileExists(path: String): Boolean =
        RootShell.runBestEffort("if [ -e $path ]; then echo y; else echo n; fi")?.trim() == "y"
}
