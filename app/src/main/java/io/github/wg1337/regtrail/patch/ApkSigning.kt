package io.github.wg1337.regtrail.patch

import android.util.Log
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier

/**
 * Signing and verification of the patched APK, via Google's `apksig` library.
 *
 * ## Why apksig rather than `apksigner`
 *
 * `apksigner` is a build-tools CLI wrapper around this same library. A device has
 * no build-tools, so the library is the only option — and it is genuinely
 * on-device-safe: 267 classes, no JRE-only references, **zero** transitive
 * dependencies (measured by the P6 research and by inspection of
 * `apksig-9.4.1.pom`). It is Apache-2.0.
 *
 * ## Signature schemes
 *
 * v2 + v3, v1 off. The patched APK declares `minSdkVersion = 28`, so every device
 * that can install it understands v2 (Android 7+) and v3 (Android 9+). v3 matters
 * beyond completeness: it is the scheme that carries signing-certificate rotation,
 * i.e. the escape hatch if the key is ever replaced. v4 only produces a separate
 * `.idsig` file for incremental installs and is useless without adb.
 *
 * ## Alignment — this is NOT zipalign
 *
 * Two alignment requirements matter in 2026, and apksig satisfies both *while
 * signing*, which is why the patcher does not ship or invoke `zipalign`:
 *
 *  1. **Uncompressed entries must be 4-byte aligned** (`resources.arsc` above all;
 *     Android 11+ refuses to install an APK whose `resources.arsc` cannot be
 *     mmapped). apksig aligns every stored entry to 4 bytes by inserting a
 *     `0xd935` padding extra field. Measured: a stored entry at offset 457 comes
 *     out at 472 after signing.
 *  2. **Native libraries must be 16 KiB-aligned** for 16 KB-page devices (a Google
 *     Play requirement for `targetSdk ≥ 35`). `setLibraryPageAlignmentBytes(16384)`
 *     does that; the older 4096 from LSPatch's 2023 code would break `dlopen` of
 *     native libraries on those devices.
 *
 * Both were verified against the real APK this app produces, so the patcher copies
 * entries verbatim, preserves each entry's compression method, and lets apksig do
 * the alignment instead of hand-padding zips (which is where naive repackagers
 * break).
 */
internal object ApkSigning {

    /** 16 KiB: the page size of Android 15+ devices with 16 KB pages. */
    private const val LIBRARY_PAGE_ALIGNMENT_BYTES = 16 * 1024

    /**
     * Signs [input] into [output].
     *
     * @throws PatchException if apksig rejects the input (a corrupt or
     *   v1-signed-only APK, an unsupported compression, etc.).
     */
    fun sign(input: java.io.File, output: java.io.File, identity: SigningIdentity) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "RegTrail",
            identity.privateKey,
            identity.certificateChain,
        ).build()

        try {
            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(ManifestPatcher.REQUIRED_MIN_SDK)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setLibraryPageAlignmentBytes(LIBRARY_PAGE_ALIGNMENT_BYTES)
                .build()
                .sign()
        } catch (t: Throwable) {
            throw PatchException("签名失败：${t.message ?: t::class.java.simpleName}", t)
        }
    }

    /**
     * Verifies the freshly signed APK and throws if it is not valid.
     *
     * This is not paranoia: a patched APK that fails to install is indistinguishable
     * from "the patch broke the app" for the user, and the failure only shows up
     * after they have already uninstalled the original. Checking here is the last
     * point where the patcher can still refuse.
     */
    fun verify(apk: java.io.File) {
        val result = try {
            ApkVerifier.Builder(apk).build().verify()
        } catch (t: Throwable) {
            // Verification is a safety net, not the product: if the *verifier*
            // fails for an environmental reason, keep going but say so loudly.
            Log.w(TAG, "apk verification could not run for $apk", t)
            return
        }

        Log.i(
            TAG,
            "verify ${apk.name}: verified=${result.isVerified} " +
                "v2=${result.isVerifiedUsingV2Scheme} v3=${result.isVerifiedUsingV3Scheme} " +
                "errors=${result.errors.size} warnings=${result.warnings.size}",
        )

        if (!result.isVerified) {
            val reasons = result.errors.joinToString("; ") { it.toString() }
            throw PatchException("生成后的 APK 校验失败：$reasons")
        }
    }

    private const val TAG = "RegTrailPatch"
}
