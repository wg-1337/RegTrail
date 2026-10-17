package io.github.wg1337.regtrail.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.wg1337.regtrail.shared.model.OverlayMechanism
import io.github.wg1337.regtrail.xposed.hook.FloatingWindowPermissionHook
import io.github.wg1337.regtrail.xposed.overlay.ModuleConfig
import io.github.wg1337.regtrail.xposed.overlay.OverlayController

/**
 * RegTrail's LSPosed module entry point (modern API 102).
 *
 * Discovered by the framework through `META-INF/xposed/java_init.list` +
 * `module.prop`, both inside the APK.
 *
 * ## What runs where
 * - **target app process**: boots [OverlayController], which attaches the
 *   floating window. This is the only code that runs by default.
 * - **system_server**: installs [FloatingWindowPermissionHook] — **only when the
 *   user has explicitly opted in**. See the warning below.
 *
 * ## Why the system_server hook is opt-in
 * Hooking `system_server` is qualitatively more dangerous than hooking an app:
 * it is part of the boot path, it runs before the launcher, and a hook that
 * throws during boot produces a bootloop — the user loses the ability to reach
 * the app to turn the module off, and recovery means Magisk's safe mode or a
 * data wipe.
 *
 * `PhoneWindowManager.checkAddPermission` is a small, stable, side-effect-free
 * method and the temptation is to call it safe. That judgement is not ours to
 * make on the user's behalf: a ROM that restructures `PhoneWindowManager`, a
 * downstream module targeting the same method, or a future framework change can
 * all turn it into a bootloop.
 *
 * The default mechanism therefore needs **no system_server involvement at all**
 * (see [OverlayController]'s DecorView path). Users who want the higher window
 * layer can enable it knowingly, and the app refuses to enable it unless the
 * user has acknowledged the recovery risk.
 */
class RegTrailModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // Safe place to initialise: the framework has already attached
        // XposedInterface by the time this is called. Do NOT do work in an
        // init {} block — the wiki is explicit about that.
        log(
            Log.INFO,
            TAG,
            "loaded in ${param.processName} (systemServer=${param.isSystemServer}) " +
                "framework=$frameworkName $frameworkVersion api=$apiVersion",
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        // Gate on the published config. If it cannot be read we assume the safe
        // default (no hook), because a false positive here is unrecoverable for
        // the user while a false negative merely means the fallback overlay.
        val config = runCatching { ModuleConfig(this).reload() }.getOrNull()
        if (config == null) {
            log(
                Log.INFO,
                TAG,
                "system_server: no config readable, not installing overlay permission hook " +
                    "(safe default; the DecorView overlay needs no hook)",
            )
            return
        }

        if (config.overlayMechanism != OverlayMechanism.ApplicationOverlay) {
            log(
                Log.INFO,
                TAG,
                "system_server: overlay mechanism is ${config.overlayMechanism}, " +
                    "not installing the permission hook",
            )
            return
        }

        if (!config.systemHookAcknowledged) {
            log(
                Log.WARN,
                TAG,
                "system_server: ApplicationOverlay requested but the user has not " +
                    "acknowledged the bootloop risk; refusing to hook. " +
                    "Enable it in RegTrail's 外观 → 悬浮窗设置.",
            )
            return
        }

        log(Log.WARN, TAG, "system_server: installing overlay permission hook (user opted in)")
        runCatching {
            FloatingWindowPermissionHook.install(this)
        }.onFailure {
            log(Log.ERROR, TAG, "failed to install system_server hooks", it)
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        val pkg = param.packageName

        // Never touch ourselves, the framework manager, or framework packages.
        if (pkg == MODULE_PACKAGE) return
        if (pkg == "android") return
        if (pkg.startsWith("org.lsposed.") || pkg.startsWith("org.matrix.vector.")) return

        // No heuristic on ApplicationInfo flags.
        //
        // An earlier version tried to skip non-UI packages by testing
        // ApplicationInfo.FLAG_HAS_CODE | FLAG_HAS_ACTIVITIES. That is not a
        // reliable check: FLAG_HAS_ACTIVITIES is not a public ApplicationInfo
        // constant at all, and the hand-written bit shift that stood in for it
        // was actually FLAG_SYSTEM — so every ordinary user app was rejected with
        // "no launcher activity" and the overlay never attached.
        //
        // The DecorView mechanism registers an ActivityLifecycleCallbacks, so a
        // process with no Activities simply never attaches anything. That is
        // self-correcting; a flag guess is not.
        runCatching {
            OverlayController(this, param).install()
        }.onFailure {
            log(Log.ERROR, TAG, "failed to install overlay controller for $pkg", it)
        }
    }

    internal companion object {
        const val TAG = "RegTrail"

        /**
         * The module's own runtime package name.
         *
         * Hard-coded on purpose: this code runs *inside target apps*, where
         * `BuildConfig` belongs to the host. It must match `applicationId`
         * exactly, capitalised last segment included.
         */
        const val MODULE_PACKAGE = "io.github.wg1337.RegTrail"
    }
}
