package io.github.wg1337.regtrail.shared.model

/**
 * Result of the in-app environment self-check.
 *
 * RegTrail needs two independent capabilities, and both are reported separately
 * so the UI can tell the user exactly which one is missing:
 *   - **Root** (any of Magisk / KernelSU / APatch / other)
 *   - **LSPosed** (the framework module) running on top of Zygisk
 */
data class SelfCheckReport(
    val device: DeviceInfo,
    val root: RootInfo,
    val lsposed: LsposedInfo,
) {
    /** True when every prerequisite for the LSPosed module path is satisfied. */
    val ready: Boolean
        get() = root.granted && lsposed.installed && !lsposed.zygiskBlocking
}

/** Facts about the device that affect which components we can load. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** Primary ABI, e.g. `arm64-v8a`. */
    val primaryAbi: String,
    /** All ABIs the device supports, most preferred first. */
    val supportedAbis: List<String>,
    val appPackageName: String,
    val appVersionName: String,
)

/**
 * Root-detection result.
 *
 * Deliberately *not* based on `File("/system/bin/su").exists()`: KernelSU and
 * APatch fake `su` inside the kernel by hooking `faccessat`/`execve` only, so a
 * filesystem existence check can be false while `exec("su")` works perfectly.
 * The authoritative signal is whether a root shell can actually be obtained.
 */
data class RootInfo(
    /** Whether a root shell was successfully obtained. */
    val granted: Boolean,
    /** Best-effort identification of the su implementation. */
    val manager: RootManager,
    /** Raw `su -v` (or equivalent) output, if any. */
    val versionString: String? = null,
    /** Populated when the check failed, so the UI can explain why. */
    val error: String? = null,
)

enum class RootManager {
    Magisk,
    KernelSU,
    APatch,
    Other,
    None,
}

/**
 * LSPosed framework detection.
 *
 * Detection strategy (see docs/PLAN.md 4.5.2 — RegTrail targets LSPosed only):
 *  1. Read `/data/adb/modules/zygisk_lsposed/module.prop` for version/versionCode.
 *     The module directory id is `zygisk_lsposed` — confirmed from the official
 *     release package's own `module.prop`.
 *  2. Confirm Zygisk is enabled (LSPosed requires it). Zygisk state may be
 *     genuinely unknowable on KernelSU/APatch (the `zygisk` key lives in
 *     Magisk's `magisk.db`), hence [ZygiskState.Unknown] rather than a boolean.
 *  3. Confirm the installed ABI set matches the device's.
 */
data class LsposedInfo(
    val installed: Boolean,
    val versionName: String? = null,
    val versionCode: String? = null,
    val zygiskState: ZygiskState = ZygiskState.Unknown,
    /** True when LSPosed ships a binary for this device's primary ABI. */
    val abiSupported: Boolean = false,
    /** Human-readable notes, e.g. what was probed and what was found. */
    val notes: List<String> = emptyList(),
    val error: String? = null,
) {
    /**
     * Whether Zygisk is a *blocker*. Only an explicit "disabled" blocks us:
     * LSPosed cannot even load its module directory without Zygisk, so if the
     * module directory exists, Zygisk is in practice working — we just may not
     * have been able to read the setting.
     */
    val zygiskBlocking: Boolean get() = zygiskState == ZygiskState.Disabled
}

/** Three-valued Zygisk state, because "cannot determine" is not "disabled". */
enum class ZygiskState {
    Enabled,
    Disabled,
    Unknown,
}

/** One line in the self-check UI: a label, a value, and how to interpret it. */
data class CheckRow(
    val label: String,
    val value: String,
    val status: CheckStatus,
    val detail: String? = null,
)

enum class CheckStatus {
    Ok,
    Warning,
    Failed,
    Info,
    Pending,
}
