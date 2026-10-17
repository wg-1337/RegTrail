package io.github.wg1337.regtrail.scope

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.wg1337.regtrail.shared.model.TargetApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Talks to the LSPosed framework on behalf of the settings app.
 *
 * The framework exposes itself through a ContentProvider
 * (`${applicationId}.XposedService`, shipped by `io.github.libxposed:service`).
 * Registering a listener with [XposedServiceHelper] binds us to it and gives us
 * a [XposedService] handle, which is the *supported* way to:
 *
 *  - read and request the module's scope ([grantScope]),
 *  - enumerate apps currently running with the module injected,
 *  - read/write remote preferences shared with the injected module.
 *
 * ## What is deliberately NOT used
 * `lspctl`, the framework's CLI, is documented by its authors as "an interactive
 * debugging tool for humans and AI agents ... not a stable control protocol",
 * and its mutating commands are restricted to root shells that originate from
 * ADB. Building an app on top of it would be both unsupported and broken, so it
 * is only ever suggested to the user for manual troubleshooting.
 */
class ScopeManager(private val context: Context) {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var service: XposedService? = null

    private val listener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(bound: XposedService) {
            service = bound
            Log.i(TAG, "bound to ${bound.frameworkName} ${bound.frameworkVersion}")
            refresh()
        }

        override fun onServiceDied(dead: XposedService) {
            Log.w(TAG, "framework service died")
            service = null
            _state.value = _state.value.copy(
                bound = false,
                scope = emptyList(),
                runningTargets = emptyList(),
            )
        }
    }

    fun register() {
        runCatching { XposedServiceHelper.registerListener(listener) }
            .onFailure { Log.w(TAG, "failed to register XposedService listener", it) }
    }

    /** Re-reads framework state. Cheap; safe to call on resume. */
    fun refresh() {
        val svc = service ?: run {
            _state.value = _state.value.copy(bound = false)
            return
        }
        runCatching {
            _state.value = State(
                bound = true,
                frameworkName = svc.frameworkName,
                frameworkVersion = svc.frameworkVersion,
                frameworkVersionCode = svc.frameworkVersionCode,
                apiVersion = svc.apiVersion,
                supportsRemote = svc.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L,
                supportsSystem = svc.frameworkProperties and XposedService.PROP_CAP_SYSTEM != 0L,
                scope = svc.scope.orEmpty(),
                runningTargets = svc.runningTargets.orEmpty(),
            )
        }.onFailure {
            Log.w(TAG, "failed to read framework state", it)
            _state.value = _state.value.copy(bound = false)
        }
    }

    /**
     * Asks the framework to add [packages] to the module's scope.
     *
     * The framework raises **one** user-facing notification prompt covering the
     * whole list (it has a one-minute timeout). Approving it answers for every
     * package in the request, so this is batched on purpose.
     *
     * @param onResult called on the caller's thread with a human-readable
     *   outcome, because the flows that need it are error-reporting UIs.
     */
    fun grantScope(
        packages: List<String>,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        val svc = service
        if (svc == null) {
            onResult(false, "尚未连接到 LSPosed 框架，请确认模块已启用并重启")
            return
        }
        val targets = packages.filter { it.isNotBlank() }.distinct().sorted()
        if (targets.isEmpty()) {
            onResult(false, "没有需要申请的包名")
            return
        }

        runCatching {
            svc.requestScope(
                targets,
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        refresh()
                        onResult(true, "已授权 ${approved.size} 个应用")
                    }

                    override fun onScopeRequestFailed(message: String) {
                        onResult(false, message)
                    }
                },
            )
        }.onFailure { onResult(false, it.message ?: "申请作用域失败") }
    }

    fun revokeScope(packages: List<String>, onResult: (Boolean, String) -> Unit) {
        val svc = service ?: run {
            onResult(false, "尚未连接到 LSPosed 框架")
            return
        }
        runCatching {
            svc.removeScope(packages)
            refresh()
            onResult(true, "已移除 ${packages.size} 个应用")
        }.onFailure { onResult(false, it.message ?: "移除作用域失败") }
    }

    /**
     * Apps that are currently running with our module loaded.
     *
     * Note these are only the *live* ones; the authoritative list is the scope
     * the framework reports, which is why the UI shows both.
     */
    fun runningTargets(): List<HookedTarget> = service?.runningTargets.orEmpty()

    /** Framework name, for display. Null when not bound. */
    val frameworkName: String? get() = service?.frameworkName

    // -----------------------------------------------------------------------

    data class State(
        val bound: Boolean = false,
        val frameworkName: String? = null,
        val frameworkVersion: String? = null,
        val frameworkVersionCode: Long = 0,
        val apiVersion: Int = 0,
        val supportsRemote: Boolean = false,
        val supportsSystem: Boolean = false,
        /** Packages the user has granted to the module. */
        val scope: List<String> = emptyList(),
        val runningTargets: List<HookedTarget> = emptyList(),
    ) {
        /** The module needs system_server in scope for the permission bypass. */
        val hasSystemServerScope: Boolean
            get() = scope.any { it == "system" || it == "android" || it == "system_server" }
    }

    private companion object {
        const val TAG = "RegTrail"
    }
}

/** Enumerates installed apps suitable as injection targets. */
class AppCatalog(private val context: Context) {

    fun load(): List<TargetApp> {
        val pm = context.packageManager
        val self = context.packageName

        // `ApplicationInfoFlags` only exists from API 33. Calling it on older
        // releases throws and — because this used to sit inside a bare
        // `runCatching { … }.getOrElse { return emptyList() }` — silently produced
        // an empty app list on Android 12 and below, which looked exactly like
        // "package visibility is broken". Measured on an API 31 device.
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        }.getOrElse { t ->
            Log.e(
                "RegTrail",
                "getInstalledApplications failed (sdk=${Build.VERSION.SDK_INT})",
                t,
            )
            return emptyList()
        }

        Log.i("RegTrail", "AppCatalog: ${installed.size} installed applications enumerated")

        val result = installed.asSequence()
            .filter { it.packageName != self }
            // Only apps with a launcher entry are interesting: everything the
            // overlay can decorate is something the user can open.
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                TargetApp(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    uid = info.uid,
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
            .toList()

        Log.i("RegTrail", "AppCatalog: ${result.size} launchable targets after filtering")
        return result
    }
}
