package io.github.wg1337.regtrail.xposed.hook

import android.util.Log
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Removes the `SYSTEM_ALERT_WINDOW` requirement for our own overlay windows.
 *
 * ## The problem
 * A floating window that must survive Activity changes has to be added to the
 * global window manager as a `TYPE_APPLICATION_OVERLAY` window. For that, the
 * *host* app would normally need the `SYSTEM_ALERT_WINDOW` permission — which
 * RegTrail cannot grant itself, because the permission belongs to the target,
 * and asking the user to toggle "display over other apps" for every target is
 * a poor experience (and impossible for exported no-root builds).
 *
 * ## The mechanism
 * Verified against AOSP `PhoneWindowManager.checkAddPermission(...)` on the
 * android14 / android15 / android16 release branches — the signature is
 * identical on all three, and it is the **single server-side gate** for adding
 * any window:
 *
 * ```
 * WindowManagerService.addWindow(...)
 *   └─ mPolicy.checkAddPermission(type, isRoundedCornerOverlay, packageName, appOp)
 *        if (res != ADD_OKAY) return res;          // <- permission denied here
 * ```
 *
 * Inside `checkAddPermission`, once the type is recognised as a system-alert
 * window type (which `TYPE_APPLICATION_OVERLAY` is):
 *
 * ```java
 * outAppOp[0] = OP_SYSTEM_ALERT_WINDOW;
 * if (checkCallingOrSelfPermission(SYSTEM_APPLICATION_OVERLAY) == GRANTED) return ADD_OKAY;
 * final int mode = mAppOpsManager.noteOpNoThrow(appOp, callingUid, packageName, ...);
 * switch (mode) {
 *     case MODE_ALLOWED:
 *     case MODE_IGNORED: return ADD_OKAY;
 *     case MODE_ERRORED: return targetSdk < M ? ADD_OKAY : ADD_PERMISSION_DENIED;
 * }
 * ```
 *
 * So there are exactly two ways to be denied: a missing signature permission,
 * or an app-op in `MODE_ERRORED`. We return `ADD_OKAY` ourselves and set
 * `outAppOp[0] = OP_NONE`.
 *
 * ## Why `OP_NONE` and not "pretend it is allowed"
 * If the app-op stays `OP_SYSTEM_ALERT_WINDOW`, `WindowState` consults it when
 * computing opacity, and a `MODE_IGNORED` result makes the window **invisible**
 * even though it was added successfully. `OP_NONE` means "no app-op governs
 * this window", which is what an unmanaged window should report.
 *
 * ## Scope of the bypass
 * The hook never blanket-allows overlay windows. It only answers `ADD_OKAY` for
 * the packages RegTrail has actually been scoped to, read through
 * [TargetRegistry]. Anything else falls through to the original method with
 * untouched semantics.
 */
internal object FloatingWindowPermissionHook {

    private const val TAG = "RegTrail"

    /** `PhoneWindowManager` lives in the framework, loaded by the boot classloader. */
    private const val PWM_CLASS = "com.android.server.policy.PhoneWindowManager"

    private const val METHOD_NAME = "checkAddPermission"

    /** `WindowManagerGlobal.ADD_OKAY`. */
    private const val ADD_OKAY = 0

    /** `AppOpsManager.OP_NONE`. */
    private const val OP_NONE = -1

    /** `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`. */
    private val OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    fun install(module: XposedModule) {
        val method = findCheckAddPermission()
        if (method == null) {
            module.log(
                Log.WARN,
                TAG,
                "PhoneWindowManager#$METHOD_NAME not found; the permission-free overlay " +
                    "mechanism is unavailable on this ROM. RegTrail will fall back to a " +
                    "DecorView-attached window.",
            )
            return
        }

        module.hook(method)
            .setId("regtrail.checkAddPermission")
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (tryBypass(chain, module)) {
                    ADD_OKAY
                } else {
                    chain.proceed()
                }
            }

        module.log(Log.INFO, TAG, "installed ${method.declaringClass.name}#$METHOD_NAME")
    }

    /**
     * @return true when this call is one of ours and should be allowed.
     */
    private fun tryBypass(
        chain: XposedInterface.Chain,
        module: XposedModule,
    ): Boolean {
        return try {
            val type = chain.getArg(0) as? Int ?: return false
            if (type != OVERLAY_TYPE) return false

            val packageName = chain.getArg(2) as? String ?: return false
            if (!TargetRegistry.isTarget(packageName)) return false

            // outAppOp is a single-element int[]; clear it so WindowState does
            // not hide an otherwise-successful window.
            (chain.getArg(3) as? IntArray)?.let { outAppOp ->
                if (outAppOp.isNotEmpty()) outAppOp[0] = OP_NONE
            }

            module.log(Log.DEBUG, TAG, "allowing overlay window for $packageName")
            true
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "bypass check failed, falling through", t)
            false
        }
    }

    /**
     * `checkAddPermission` is a public method on a hidden class in the
     * `android` package, so plain reflection works once the class is visible.
     * The signature has been stable across Android 14-16:
     * `(int, boolean, String, int[])`.
     */
    private fun findCheckAddPermission(): Method? {
        return try {
            val cls = Class.forName(PWM_CLASS, false, ClassLoader.getSystemClassLoader())
            cls.methods.firstOrNull {
                it.name == METHOD_NAME &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[2] == String::class.java &&
                    it.parameterTypes[3] == IntArray::class.java
            }
        } catch (t: Throwable) {
            null
        }
    }
}
