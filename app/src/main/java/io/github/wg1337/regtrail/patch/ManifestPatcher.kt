package io.github.wg1337.regtrail.patch

import com.wind.meditor.core.ManifestEditor
import com.wind.meditor.property.AttributeItem
import com.wind.meditor.property.ModificationProperty
import pxb.android.axml.AxmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The facts from a target APK's binary manifest that the patcher needs.
 *
 * @param packageName `package` of the target. Read (not assumed) because the
 *   patcher is handed a package name by the UI and the APK on disk is the
 *   authority — a mismatch means we were pointed at the wrong file.
 * @param appComponentFactory the factory the target already declares, if any. It
 *   is *replaced* by the payload factory, so it is captured first: the outcome
 *   reports it and it is recorded in the patched manifest as `meta-data`.
 * @param minSdkVersion absent `uses-sdk` means 1 (the platform default).
 */
internal data class TargetManifestFacts(
    val packageName: String?,
    val applicationName: String?,
    val appComponentFactory: String?,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
)

/**
 * Reads and rewrites the **binary** `AndroidManifest.xml`.
 *
 * ## Why a binary-AXML library and not text
 *
 * An APK's manifest is not XML text: it is the compiled `AXML` form with a string
 * pool and resource-ID-keyed attributes. Editing it means re-encoding that
 * structure, and a hand-rolled encoder that emits an attribute without its
 * `android:` resource ID produces a manifest the platform *silently ignores* —
 * e.g. an `appComponentFactory` that never gets used, so the patch "succeeds" and
 * the overlay never appears.
 *
 * `com.github.WindySha:ManifestEditor` (Apache-2.0, JitPack) is the library both
 * Xpatch and LSPatch use for exactly this, and it resolves attribute names to the
 * correct resource ID (measured: the injected `appComponentFactory` comes out as
 * `android:appComponentFactory` with resource ID `0x0101057a`).
 *
 * ## Why `minSdkVersion` is passed as an `Int`
 *
 * `AttributeItem`'s value type decides the AXML value type. A `String` would be
 * written as `TYPE_STRING` (which the platform tolerates by parsing the text), an
 * `Int` is written as `TYPE_INT_DEC` — which is what `aapt2` produces and what the
 * platform expects. Measured on the real manifest: the value type stays `0x10`.
 */
internal object ManifestPatcher {

    /**
     * The payload factory that replaces whatever the target declared.
     *
     * Kept as a literal on purpose: `:app` does not depend on `:payload` (it only
     * consumes the generated `payload.dex`), and a compile-time dependency would
     * put the payload's classes into RegTrail's own dex for no reason.
     */
    const val FACTORY_CLASS = "io.github.wg1337.regtrail.payload.RegTrailAppFactory"

    /**
     * `meta-data` key that records the factory that was replaced.
     *
     * Nothing reads it at runtime yet: the payload calls
     * `super.instantiateApplication` (that is the whole design — the original
     * Application is created by the platform, untouched) and delegating the other
     * instantiation hooks to a replaced factory is not implemented. It is recorded
     * so the information is not lost inside the patched APK, which makes both
     * debugging and a future delegating payload possible without re-patching.
     */
    const val ORIGINAL_FACTORY_META = "io.github.wg1337.regtrail.ORIGINAL_ACF"

    /** `AppComponentFactory` does not exist below API 28. */
    const val REQUIRED_MIN_SDK = 28

    /**
     * Lowest `targetSdkVersion` the platform will still install (Android 15 floor).
     *
     * This is *not* something the patch can fix: raising `targetSdkVersion` changes
     * the app's behaviour contract and can break it. The patcher therefore refuses
     * such targets with an explanation instead of guessing.
     */
    const val MIN_INSTALLABLE_TARGET_SDK = 24

    private const val TAG_MANIFEST = "manifest"
    private const val TAG_USES_SDK = "uses-sdk"
    private const val TAG_APPLICATION = "application"
    private const val ATTR_PACKAGE = "package"
    private const val ATTR_NAME = "name"
    private const val ATTR_MIN_SDK = "minSdkVersion"
    private const val ATTR_TARGET_SDK = "targetSdkVersion"
    private const val ATTR_APP_COMPONENT_FACTORY = "appComponentFactory"

    /** Parses the attributes the patcher cares about out of [manifest] (AXML bytes). */
    fun read(manifest: ByteArray): TargetManifestFacts {
        var packageName: String? = null
        var applicationName: String? = null
        var factory: String? = null
        var minSdk = 1
        var targetSdk = 1

        val parser = AxmlParser(manifest)
        while (true) {
            val event = parser.next()
            if (event == AxmlParser.END_FILE) break
            if (event != AxmlParser.START_TAG) continue

            val tag = parser.name ?: continue
            when (tag) {
                TAG_MANIFEST -> {
                    for (index in 0 until parser.attrCount) {
                        if (parser.getAttrName(index) == ATTR_PACKAGE) {
                            packageName = parser.getAttrValue(index)?.toString()
                        }
                    }
                }

                TAG_USES_SDK -> {
                    for (index in 0 until parser.attrCount) {
                        when (parser.getAttrName(index)) {
                            ATTR_MIN_SDK -> minSdk = intValue(parser.getAttrValue(index), minSdk)
                            ATTR_TARGET_SDK -> targetSdk = intValue(parser.getAttrValue(index), targetSdk)
                        }
                    }
                }

                TAG_APPLICATION -> {
                    for (index in 0 until parser.attrCount) {
                        when (parser.getAttrName(index)) {
                            ATTR_NAME -> applicationName = parser.getAttrValue(index)?.toString()
                            ATTR_APP_COMPONENT_FACTORY ->
                                factory = parser.getAttrValue(index)?.toString()
                        }
                    }
                }
            }
        }

        return TargetManifestFacts(
            packageName = packageName,
            applicationName = applicationName,
            appComponentFactory = factory,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
        )
    }

    /**
     * Rewrites [manifest] so the platform instantiates [FACTORY_CLASS] and the
     * payload's API floor is respected.
     *
     * Everything else — string pool, resource IDs, component declarations,
     * permissions — is preserved by ManifestEditor, which is the point: the
     * patched app must behave exactly like the original apart from the factory.
     *
     * @throws PatchException if the rewritten manifest does not read back with the
     *   expected values. That check is cheap and turns "the patch silently did
     *   nothing" into a clear error, which is the failure mode this whole feature
     *   is most vulnerable to.
     */
    fun patch(manifest: ByteArray, originalFactory: String?): ByteArray {
        val property = ModificationProperty()
            .addApplicationAttribute(AttributeItem(ATTR_APP_COMPONENT_FACTORY, FACTORY_CLASS))
            .addUsesSdkAttribute(
                AttributeItem(ATTR_MIN_SDK, Integer.valueOf(REQUIRED_MIN_SDK)),
            )

        if (!originalFactory.isNullOrBlank() && originalFactory != FACTORY_CLASS) {
            property.addMetaData(
                ModificationProperty.MetaData(ORIGINAL_FACTORY_META, originalFactory),
            )
        }

        val output = ByteArrayOutputStream(manifest.size + 512)
        ManifestEditor(ByteArrayInputStream(manifest), output, property).processManifest()
        val patched = output.toByteArray()

        val verified = read(patched)
        if (verified.appComponentFactory != FACTORY_CLASS) {
            throw PatchException(
                "改写后的 AndroidManifest.xml 未包含 ${FACTORY_CLASS}" +
                    "（读到 ${verified.appComponentFactory ?: "空"}），已中止",
            )
        }
        if (verified.minSdkVersion < REQUIRED_MIN_SDK) {
            throw PatchException(
                "改写后的 minSdkVersion=${verified.minSdkVersion}，仍低于 ${REQUIRED_MIN_SDK}，已中止",
            )
        }
        return patched
    }

    private fun intValue(value: Any?, fallback: Int): Int = when (value) {
        is Number -> value.toInt()
        null -> fallback
        else -> value.toString().toIntOrNull() ?: fallback
    }
}

/** A failure the user can act on, with a message written for the UI. */
internal class PatchException(message: String, cause: Throwable? = null) : Exception(message, cause)
