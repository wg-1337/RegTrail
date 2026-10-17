package io.github.wg1337.regtrail.xposed.overlay

import io.github.libxposed.api.XposedModule
import io.github.wg1337.regtrail.shared.bridge.BridgeApi
import io.github.wg1337.regtrail.shared.bridge.SharedPaths
import io.github.wg1337.regtrail.shared.model.ModuleConfigSnapshot
import io.github.wg1337.regtrail.shared.model.OverlayMechanism
import io.github.wg1337.regtrail.shared.model.OverlaySizeLimits
import io.github.wg1337.regtrail.xposed.RegTrailLog
import java.io.File

/**
 * Module-side view of the configuration published by the settings app.
 *
 * The settings app writes `/data/local/tmp/regtrail/config.json` (see
 * `ConfigPublisher` in `:app`). The module reads it lazily and only re-parses
 * when the file's size or modification time changes, so a hot path such as
 * "should I attach in this process?" costs one `stat`.
 *
 * A malformed or newer-than-understood snapshot is ignored in favour of the
 * last good one, so a bad write from the app can never brick injection.
 */
internal class ModuleConfig(private val module: XposedModule) {

    @Volatile
    private var cached: Snapshot = Snapshot()

    @Volatile
    private var lastModified: Long = -1
    @Volatile
    private var lastLength: Long = -1

    /** Reloads if the file changed, then returns the current snapshot. */
    fun snapshot(): Snapshot = reload()

    fun reload(): Snapshot {
        val file = File(SharedPaths.CONFIG_CACHE)
        if (!file.isFile) {
            return cached
        }
        val modified = file.lastModified()
        val length = file.length()
        if (modified == lastModified && length == lastLength) {
            return cached
        }
        lastModified = modified
        lastLength = length

        val parsed = runCatching {
            val text = file.readText(Charsets.UTF_8)
            val model = BridgeApi.json.decodeFromString(
                ModuleConfigSnapshot.serializer(),
                text,
            )
            if (model.version > ModuleConfigSnapshot.VERSION) {
                RegTrailLog.w(
                    "config version ${model.version} is newer than this module understands " +
                        "(${ModuleConfigSnapshot.VERSION}); ignoring it",
                )
                return cached
            }
            Snapshot.from(model)
        }.getOrElse {
            RegTrailLog.w("failed to parse ${file.absolutePath}", it)
            return cached
        }

        cached = parsed
        RegTrailLog.i(
            "config loaded: enabled=${parsed.enabled} " +
                "targets=${parsed.targetPackages.size} revision=${parsed.revision}",
        )
        return parsed
    }

    val enabled: Boolean get() = reload().enabled

    fun isOverlayTarget(packageName: String): Boolean = reload().shouldAttach(packageName)

    /**
     * Immutable, pre-parsed form used inside the hot paths.
     *
     * Kept separate from the serialized model so the module never accidentally
     * mutates shared state, and so future model changes only touch [from].
     */
    data class Snapshot(
        val enabled: Boolean = true,
        val targetPackages: Set<String> = emptySet(),
        val attachAllScoped: Boolean = false,
        val excludedPackages: Set<String> = emptySet(),
        val overlayMechanism: OverlayMechanism = OverlayMechanism.DecorView,
        val systemHookAcknowledged: Boolean = false,
        val overlayPrimary: Long = 0xFFFFB300,
        val overlayFontScale: Float = 1.0f,
        val overlayAlpha: Float = 0.95f,
        val overlayCornerDp: Int = 14,
        val overlayDefaultX: Float = 0.75f,
        val overlayDefaultY: Float = 0.25f,
        /** Published starting size of the expanded panel, in dp. */
        val overlayWidthDp: Int = OverlaySizeLimits.DEFAULT_WIDTH_DP,
        val overlayHeightDp: Int = OverlaySizeLimits.DEFAULT_HEIGHT_DP,
        val panels: List<String> = listOf("status", "log"),
        val revision: Long = 0,
        val raw: ModuleConfigSnapshot = ModuleConfigSnapshot(),
    ) {
        fun shouldAttach(packageName: String): Boolean {
            if (!enabled) return false
            if (packageName in excludedPackages) return false
            if (attachAllScoped) return true
            return packageName in targetPackages
        }

        /**
         * True only when the higher-window-layer mechanism is both selected and
         * acknowledged. Anything else means the safe DecorView path.
         */
        val useSystemOverlay: Boolean
            get() = overlayMechanism == OverlayMechanism.ApplicationOverlay &&
                systemHookAcknowledged

        /**
         * Serializes for the web UI.
         *
         * Built with `BridgeApi.json` rather than string concatenation so the
         * page always receives valid JSON (colours and scales included).
         */
        fun toJsonString(): String = runCatching {
            BridgeApi.json.encodeToString(ModuleConfigSnapshot.serializer(), raw)
        }.getOrElse { "{}" }

        companion object {
            fun from(model: ModuleConfigSnapshot) = Snapshot(
                enabled = model.enabled,
                targetPackages = model.targetPackages.toSet(),
                attachAllScoped = model.attachAllScoped,
                excludedPackages = model.excludedPackages.toSet(),
                overlayMechanism = model.overlayMechanism,
                systemHookAcknowledged = model.systemHookAcknowledged,
                overlayPrimary = model.overlayPrimary,
                overlayFontScale = model.overlayFontScale,
                overlayAlpha = model.overlayAlpha,
                overlayCornerDp = model.overlayCornerDp,
                overlayDefaultX = model.overlayDefaultX,
                overlayDefaultY = model.overlayDefaultY,
                overlayWidthDp = model.overlayWidthDp,
                overlayHeightDp = model.overlayHeightDp,
                panels = model.panels,
                revision = model.revision,
                raw = model,
            )
        }
    }
}
