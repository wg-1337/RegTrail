package io.github.wg1337.regtrail.ui.patch

/**
 * One patchable app, as the "打包" screen sees it.
 *
 * All of these values come from `PackageManager` in `:app`; `:ui` never touches
 * `PackageManager`, which is the same split the scope screen uses.
 */
data class PatchTargetUi(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val minSdk: Int,
    val targetSdk: Int,
    val apkBytes: Long,
    val splitCount: Int,
    /**
     * True when patching raises the target's `minSdkVersion` to 28 (`AppComponentFactory`
     * needs it). Surfaced because it is the one manifest change the user should
     * know about before installing.
     */
    val minSdkWillBeRaised: Boolean,
)

/** A patched APK that already exists on this device. */
data class PatchRecordUi(
    val packageName: String,
    val label: String,
    val path: String,
    val bytes: Long,
    val createdAt: Long,
    val injectedDexEntry: String,
    val signerSha256: String,
    val warnings: List<String>,
)

/**
 * State of the persistent-injection screen.
 *
 * [stage] is a human-readable Chinese label of what the patcher is doing right now.
 * A patch takes seconds to minutes on a large APK, and a spinner with no text is
 * indistinguishable from a hang — which is exactly what makes users kill the app
 * mid-write.
 */
data class PatchUiState(
    val targets: List<PatchTargetUi> = emptyList(),
    val records: List<PatchRecordUi> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    /** Package currently being patched, so its row can show progress. */
    val activePackage: String? = null,
    val activeLabel: String? = null,
    val stage: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** SHA-256 of the signing certificate; identical across patches, by design. */
    val signerFingerprint: String? = null,
)
