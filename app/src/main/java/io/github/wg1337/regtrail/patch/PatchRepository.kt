package io.github.wg1337.regtrail.patch

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.wg1337.regtrail.scope.AppCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A launchable app that can be turned into a no-root patched APK.
 *
 * @param minSdk the *target's* declaration. The patch raises it to 28 when needed,
 *   which the UI shows because it is the one manifest change the user should know
 *   about.
 */
internal data class PatchTarget(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val minSdk: Int,
    val targetSdk: Int,
    val apkBytes: Long,
    val splitCount: Int,
) {
    /** True when the patch has to raise the target's `minSdkVersion` to 28. */
    val minSdkWillBeRaised: Boolean get() = minSdk < ManifestPatcher.REQUIRED_MIN_SDK
}

/** One APK this device has produced. Persisted, because patched apps outlive the app. */
internal data class PatchRecord(
    val packageName: String,
    val label: String,
    val path: String,
    val bytes: Long,
    val createdAt: Long,
    val versionCode: Long,
    val injectedDexEntry: String,
    val originalMinSdkVersion: Int,
    val patchedMinSdkVersion: Int,
    val originalAppComponentFactory: String?,
    val signerSha256: String,
    val warnings: List<String>,
)

/**
 * Everything the P6 screen needs from the platform layer that is not the patcher
 * itself: which apps are patchable, where patched APKs live, what was produced
 * before, and the signing identity in use.
 *
 * Deliberately synchronous: it is called from `Dispatchers.IO` by
 * `RegTrailPlatform`, which keeps the threading decision in one place and makes
 * this class trivially testable.
 */
internal class PatchRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val signingKeyStore = SigningKeyStore(File(context.filesDir, KEYSTORE_DIR))
    private val patcher = ApkPatcher(context, signingKeyStore)
    private val catalog = AppCatalog(context)

    /**
     * Where patched APKs are written.
     *
     * External app-specific storage on purpose: the user has to *install* the
     * result, which means getting a package installer to read it, and
     * `/sdcard/Android/data/<pkg>/files/patched/` is reachable by a file manager and
     * by `Intent.ACTION_VIEW` without any storage permission. It falls back to
     * internal storage only when external storage is unavailable.
     */
    fun outputDirectory(): File {
        val external = context.getExternalFilesDir(PATCHED_DIR)
        return external ?: File(context.filesDir, PATCHED_DIR)
    }

    /** The signing key's fingerprint, or null before the first patch (never generates one). */
    fun signerFingerprint(): String? = signingKeyStore.existingFingerprint()

    /**
     * Installed launchable apps, annotated with the facts that decide whether a
     * patch is likely to work.
     *
     * Reuses [AppCatalog] — the same enumeration the scope screen uses — so both
     * screens agree on what "an app you can inject into" means.
     */
    fun listTargets(): List<PatchTarget> {
        val packageManager = context.packageManager
        return catalog.load().map { app ->
            val info: ApplicationInfo? = runCatching {
                packageManager.getApplicationInfo(app.packageName, 0)
            }.getOrNull()

            PatchTarget(
                packageName = app.packageName,
                label = app.label,
                isSystem = app.isSystem,
                minSdk = info?.minSdkVersion ?: 1,
                targetSdk = info?.targetSdkVersion ?: 1,
                apkBytes = info?.sourceDir?.let { File(it).length() } ?: 0L,
                splitCount = info?.splitSourceDirs?.count { it.isNotBlank() } ?: 0,
            )
        }
    }

    /** Persisted patch history, newest first, with vanished files dropped. */
    fun loadRecords(): List<PatchRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::recordFromJson)
            }
        }.getOrElse { error ->
            Log.w(TAG, "could not parse stored patch records", error)
            return emptyList()
        }

        val existing = parsed.filter { File(it.path).isFile }
        if (existing.size != parsed.size) {
            persist(existing)
        }
        return existing
    }

    /**
     * Runs the patcher and records the result.
     *
     * Blocking; call on `Dispatchers.IO`.
     */
    fun patch(packageName: String, onStage: (PatchStage, String?) -> Unit): PatchRecord {
        val outcome = patcher.patch(packageName, outputDirectory(), onStage)

        val record = PatchRecord(
            packageName = outcome.packageName,
            label = outcome.label,
            path = outcome.outputApk.absolutePath,
            bytes = outcome.outputApk.length(),
            createdAt = System.currentTimeMillis(),
            versionCode = outcome.versionCode,
            injectedDexEntry = outcome.injectedDexEntry,
            originalMinSdkVersion = outcome.originalMinSdkVersion,
            patchedMinSdkVersion = outcome.patchedMinSdkVersion,
            originalAppComponentFactory = outcome.originalAppComponentFactory,
            signerSha256 = outcome.signerSha256,
            warnings = outcome.warnings,
        )

        // Newest first, one record per package: a stale record would show a path
        // that no longer exists (the patcher deletes and rewrites the same name).
        val merged = (listOf(record) + loadRecords().filter { it.packageName != packageName })
            .take(MAX_RECORDS)
        persist(merged)
        return record
    }

    /** Deletes a produced APK and forgets it. */
    fun deleteRecord(path: String): Boolean {
        val file = File(path)
        val removed = file.isFile && file.delete()
        persist(loadRecords().filter { it.path != path })
        return removed
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    private fun persist(records: List<PatchRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("packageName", record.packageName)
                    put("label", record.label)
                    put("path", record.path)
                    put("bytes", record.bytes)
                    put("createdAt", record.createdAt)
                    put("versionCode", record.versionCode)
                    put("injectedDexEntry", record.injectedDexEntry)
                    put("originalMinSdkVersion", record.originalMinSdkVersion)
                    put("patchedMinSdkVersion", record.patchedMinSdkVersion)
                    put("originalAppComponentFactory", record.originalAppComponentFactory)
                    put("signerSha256", record.signerSha256)
                    put("warnings", JSONArray(record.warnings))
                },
            )
        }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun recordFromJson(json: JSONObject): PatchRecord? {
        val now = System.currentTimeMillis()
        return runCatching {
            PatchRecord(
                packageName = json.getString("packageName"),
                label = json.optString("label", json.getString("packageName")),
                path = json.getString("path"),
                bytes = json.optLong("bytes", 0L),
                createdAt = json.optLong("createdAt", now),
                versionCode = json.optLong("versionCode", 0L),
                injectedDexEntry = json.optString("injectedDexEntry", "classes2.dex"),
                originalMinSdkVersion = json.optInt("originalMinSdkVersion", 0),
                patchedMinSdkVersion = json.optInt("patchedMinSdkVersion", 0),
                originalAppComponentFactory = json.optString("originalAppComponentFactory")
                    .takeIf { it.isNotBlank() && it != "null" },
                signerSha256 = json.optString("signerSha256", ""),
                warnings = json.optJSONArray("warnings")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optString(index) }
                }.orEmpty(),
            )
        }.getOrNull()
    }

    private companion object {
        const val TAG = "RegTrailPatch"
        const val PREFS_NAME = "regtrail_patch"
        const val KEY_RECORDS = "records_v1"
        const val KEYSTORE_DIR = "patch"
        const val PATCHED_DIR = "patched"
        const val MAX_RECORDS = 30
    }
}
