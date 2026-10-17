package io.github.wg1337.regtrail.xposed.hook

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Which packages RegTrail is allowed to touch.
 *
 * This exists so [FloatingWindowPermissionHook] is **not** a blanket bypass of
 * the overlay permission: it only answers `ADD_OKAY` for apps the user actually
 * scoped RegTrail to. Everything else keeps stock behaviour.
 *
 * ## How it is populated
 * The framework only calls `onPackageLoaded` for packages that are in the
 * module's scope, in every process that loads them. So simply recording each
 * package we are told about yields exactly the user's selection — no extra
 * configuration channel required, and it stays correct when the user edits the
 * scope in the manager.
 *
 * The settings app can additionally push an explicit allow-list (used for the
 * exported no-root builds, where the framework is absent), which is merged in.
 */
internal object TargetRegistry {

    private val fromFramework: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val fromConfig: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Package name of the module itself; always excluded. */
    @Volatile
    var modulePackage: String? = null

    fun registerFromFramework(packageName: String) {
        if (packageName.isNotBlank()) fromFramework.add(packageName)
    }

    /** Replaces the explicitly configured allow-list. */
    fun setConfiguredTargets(packages: Collection<String>) {
        fromConfig.clear()
        packages.filter { it.isNotBlank() }.forEach { fromConfig.add(it) }
    }

    fun isTarget(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == modulePackage) return false
        return packageName in fromConfig || packageName in fromFramework
    }

    /** Snapshot for status reporting. */
    fun snapshot(): List<String> =
        (fromFramework + fromConfig).sorted()

    /** True when an explicit allow-list has been pushed by the settings app. */
    val hasConfiguredTargets: Boolean get() = fromConfig.isNotEmpty()

    /** Test/diagnostic helper. */
    fun clear() {
        fromFramework.clear()
        fromConfig.clear()
    }

    /** Unmodifiable view kept for parity with earlier call sites. */
    @Suppress("unused")
    val all: Set<String> get() = Collections.unmodifiableSet(fromFramework + fromConfig)
}
