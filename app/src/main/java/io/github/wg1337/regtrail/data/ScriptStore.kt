package io.github.wg1337.regtrail.data

import io.github.wg1337.regtrail.shared.model.ScriptSpec
import io.github.wg1337.regtrail.shared.model.ScriptTrigger
import io.github.wg1337.regtrail.util.AtomicFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The script library: one directory per script, one file per version.
 *
 * ## Why files and not a database
 * A script is a *record of a debug session* — the user will want to read it,
 * diff two versions of it, mail it to someone, and drop it into another build.
 * A SQLite table would make every one of those a new feature (export, diff,
 * import), whereas a directory tree makes them free: `v0003.json` **is** the
 * artifact, and it is human-inspectable exactly as stored.
 *
 * ## Layout
 * ```
 * filesDir/scripts/
 *   index.json                     ← small human-readable summary (advisory)
 *   s-3f9a12bc/                    ← the script id, which is also the dir name
 *     current                      ← plain text: "v0003"  (the pointer)
 *     v0001.json                   ← the first save
 *     v0002.json
 *     v0003.json
 *   s-77aa10de/
 *     current
 *     v0001.json
 * filesDir/script-exports/         ← `exportBundle` output (outside the library)
 *   regtrail-scripts-20260218-131500.json
 * ```
 *
 * Each version file is self-contained and versioned:
 * ```json
 * {
 *   "format": "regtrail.script.version",
 *   "version": 2,
 *   "savedAt": 1771234567890,
 *   "script": { "id": "s-3f9a12bc", "name": "登录返回值改写", … }
 * }
 * ```
 *
 * ## Invariants
 *  - **A save never overwrites a version.** `writeVersion` always allocates the
 *    next free `vNNNN` slot, so history is append-only by construction: there is
 *    no code path that can truncate an existing version file.
 *  - **`current` is the pointer, not a copy.** Restoring an old version appends
 *    a *new* version containing that content and moves the pointer to it, so the
 *    history of "restored v1 on top of v3" stays visible instead of hiding an
 *    overwrite.
 *  - **`index.json` is advisory.** Listing always re-derives itself from the
 *    directories, so a deleted, stale or hand-edited index cannot lose a script;
 *    the file exists because reading five numbers out of it is easier than
 *    opening six directories by hand.
 *  - **Ids are validated, never sanitised.** An id reaching a file path is
 *    checked against [isValidId] first, so a hand-written `../..` cannot escape
 *    the library directory.
 *
 * ## Threading
 * Every mutating entry point is `@Synchronized`: the Compose screens, the Agent
 * tools and the config publisher all touch this object from different
 * dispatchers, and two concurrent `create` calls must not pick the same id.
 * File I/O is the caller's dispatcher decision (the platform uses
 * `Dispatchers.IO`).
 */
class ScriptStore(
    /** Library root; one sub-directory per script. */
    private val root: File,
    /**
     * Where [exportBundle] writes.
     *
     * Deliberately **outside** [root]: the library lists every sub-directory
     * that looks like a script, and an export landing inside it would either be
     * listed as a broken script or need a special case in the scanner.
     */
    private val exportDir: File = File(root.absoluteFile.parentFile ?: root, "script-exports"),
) {

    private val indexFile: File get() = File(root, INDEX_FILE)

    /**
     * `prettyPrint` because these files are meant to be read and compared by a
     * human; `ignoreUnknownKeys` so a field added by a later build does not make
     * an older build reject the whole script.
     */
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    /** Every script in the library, ordered by name. */
    @Synchronized
    fun list(): List<Entry> = scan()

    /** The current version of one script. */
    @Synchronized
    fun load(id: String): ScriptSpec? = readCurrent(id)

    /** One historical version, by number. */
    @Synchronized
    fun loadVersion(id: String, version: Int): ScriptSpec? = readVersionFile(id, version)?.script

    /** Version history of one script, newest first. */
    @Synchronized
    fun versions(id: String): List<VersionInfo> = readVersionFiles(id)
        .map { VersionInfo(it.version, it.savedAt, it.script.name, it.script.actions.size) }
        .sortedByDescending { it.version }

    /**
     * What the injected side receives in `config.json`.
     *
     * **Disabled scripts are included**, carrying `enabled = false`. The engine
     * on the module side already filters on that flag (`ScriptEngine.boot`), and
     * publishing the flag rather than pre-filtering keeps the snapshot an honest
     * picture of the library — a window that shows "3 个脚本（1 个已停用）" is
     * reporting what the user actually has, not what happened to be runnable.
     */
    @Synchronized
    fun publishable(): List<ScriptSpec> = scan()
        .mapNotNull { entry ->
            readCurrent(entry.id)?.copy(version = entry.currentVersion)
        }
        .sortedBy { it.name.lowercase(Locale.US) }

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    /**
     * Creates a script and its `v0001`.
     *
     * The id is generated when [spec]'s id is blank or already taken, and the
     * *stored* spec is returned — callers must use that, not their input, or
     * they will hold an id that does not exist on disk.
     */
    @Synchronized
    fun create(spec: ScriptSpec): ScriptSpec {
        val id = spec.id.takeIf { isValidId(it) && !dirOf(it).exists() } ?: freshId()
        val saved = writeVersion(id, spec.copy(id = id))
        rewriteIndex()
        return saved
    }

    /**
     * Saves [spec] as a **new version** of [id]; history is never replaced.
     *
     * @throws ScriptStoreException when [id] does not exist. Silently creating it
     *   would turn a typo in an id into a second script under a slightly wrong
     *   name, which is worse than an error the caller can show.
     */
    @Synchronized
    fun update(id: String, spec: ScriptSpec): ScriptSpec {
        requireExisting(id)
        val saved = writeVersion(id, spec.copy(id = id))
        rewriteIndex()
        return saved
    }

    /** Renames a script. Counts as a save, so it appends a version. */
    @Synchronized
    fun rename(id: String, name: String): ScriptSpec {
        val current = readCurrent(id) ?: throw ScriptStoreException("脚本不存在：$id")
        val clean = name.trim().ifEmpty { current.name }
        return update(id, current.copy(name = clean))
    }

    /** Copies the current version into a brand-new script (history is not copied). */
    @Synchronized
    fun duplicate(id: String, name: String? = null): ScriptSpec {
        val current = readCurrent(id) ?: throw ScriptStoreException("脚本不存在：$id")
        val copyName = name?.trim()?.takeIf { it.isNotEmpty() } ?: "${current.name} 副本"
        return create(current.copy(id = "", name = copyName, version = 0))
    }

    /** Removes a script and every version of it. */
    @Synchronized
    fun delete(id: String): Boolean {
        if (!isValidId(id)) return false
        val dir = dirOf(id)
        if (!dir.isDirectory) return false
        val removed = dir.deleteRecursively()
        rewriteIndex()
        return removed
    }

    /**
     * Restores [version] as the new current version.
     *
     * Appends rather than rolls back: "restore v1" on top of v3 produces v4
     * whose content equals v1, so v2 and v3 remain recoverable and the history
     * records that a restore happened.
     */
    @Synchronized
    fun restore(id: String, version: Int): ScriptSpec {
        requireExisting(id)
        val old = readVersionFile(id, version)?.script
            ?: throw ScriptStoreException("$id 没有 v$version")
        return update(id, old.copy(id = id))
    }

    // -----------------------------------------------------------------------
    // Export / import
    // -----------------------------------------------------------------------

    /**
     * Writes every script **with its full history** into one bundle file.
     *
     * Full history rather than just the current versions: the bundle is how a
     * user moves a debug session between devices, and "the version that worked
     * yesterday" is exactly the thing worth carrying. It is still small — each
     * version is a few hundred bytes of JSON.
     *
     * @return the file written, so the caller can show the path.
     */
    @Synchronized
    fun exportBundle(stamp: Long = System.currentTimeMillis()): File {
        val scripts = scan().mapNotNull { entry ->
            val files = readVersionFiles(entry.id)
            if (files.isEmpty()) {
                null
            } else {
                BundleScript(
                    id = entry.id,
                    name = entry.name,
                    current = entry.currentVersion,
                    versions = files,
                )
            }
        }

        val bundle = ScriptBundle(
            format = FORMAT_BUNDLE,
            exportedAt = stamp,
            scripts = scripts,
        )

        val target = File(exportDir, "regtrail-scripts-${stampText(stamp)}.json")
        AtomicFiles.writeText(
            target,
            json.encodeToString(ScriptBundle.serializer(), bundle),
        )
        return target
    }

    /**
     * Imports a bundle produced by [exportBundle].
     *
     * Collision policy: an id already present in the library is **not**
     * overwritten (that would silently destroy the local history the import was
     * supposed to sit next to). The incoming script gets a fresh id instead and
     * keeps all of its versions; [ImportResult.renamed] counts those.
     */
    @Synchronized
    fun importBundle(text: String): ImportResult {
        val bundle = runCatching {
            json.decodeFromString(ScriptBundle.serializer(), text)
        }.getOrElse { cause ->
            throw ScriptStoreException("不是 RegTrail 脚本包：${cause.message ?: "JSON 解析失败"}")
        }

        if (bundle.format != FORMAT_BUNDLE) {
            throw ScriptStoreException("不是 RegTrail 脚本包（format=${bundle.format}）")
        }

        var imported = 0
        var renamed = 0
        var skipped = 0
        val ids = ArrayList<String>(bundle.scripts.size)

        bundle.scripts.forEach { entry ->
            if (entry.versions.isEmpty()) {
                skipped++
                return@forEach
            }

            val keepId = isValidId(entry.id) && !dirOf(entry.id).exists()
            val targetId = if (keepId) entry.id else freshId()
            if (!keepId) renamed++

            dirOf(targetId).mkdirs()
            entry.versions.forEach { file ->
                val version = file.version.coerceAtLeast(1)
                val stored = file.copy(
                    version = version,
                    script = file.script.copy(id = targetId, version = version),
                )
                AtomicFiles.writeText(
                    versionFile(targetId, version),
                    json.encodeToString(ScriptVersionFile.serializer(), stored),
                )
            }

            val highest = entry.versions.maxOf { it.version.coerceAtLeast(1) }
            writeCurrentPointer(targetId, entry.current.coerceIn(1, highest))
            imported++
            ids += targetId
        }

        rewriteIndex()
        return ImportResult(
            imported = imported,
            renamed = renamed,
            skipped = skipped,
            ids = ids,
        )
    }

    // -----------------------------------------------------------------------
    // Internals — location
    // -----------------------------------------------------------------------

    private fun dirOf(id: String): File = File(root, id)

    private fun versionFile(id: String, version: Int): File = File(dirOf(id), versionName(version))

    private fun versionName(version: Int): String =
        String.format(Locale.US, "v%04d.json", version)

    /**
     * The pointer's *content*: `v0003`, not `v0003.json`.
     *
     * The pointer names a version, not a file name, and those two are only the
     * same string by coincidence. Copying a directory by hand and renaming the
     * version files should not be able to break the pointer, so it carries the
     * version and [currentPointer] adds the file name.
     */
    private fun pointerName(version: Int): String =
        String.format(Locale.US, "v%04d", version)

    private fun freshId(): String {
        repeat(16) {
            val candidate = "s-" + UUID.randomUUID().toString().replace("-", "").take(8)
            if (!dirOf(candidate).exists()) return candidate
        }
        // 16 collisions on a 32-bit space means something is deeply wrong; the
        // timestamp keeps the invariant "the id is unique" rather than throwing.
        return "s-" + System.currentTimeMillis().toString(36)
    }

    private fun requireExisting(id: String) {
        if (!isValidId(id)) throw ScriptStoreException("非法脚本 id：$id")
        if (!dirOf(id).isDirectory) throw ScriptStoreException("脚本不存在：$id")
    }

    // -----------------------------------------------------------------------
    // Internals — reading a script
    // -----------------------------------------------------------------------

    private fun scan(): List<Entry> {
        val dirs = root.listFiles().orEmpty()
        return dirs
            .asSequence()
            .filter { it.isDirectory && isValidId(it.name) }
            .mapNotNull { entryOf(it.name) }
            .sortedBy { it.name.lowercase(Locale.US) }
            .toList()
    }

    private fun entryOf(id: String): Entry? {
        val pointer = currentPointer(id) ?: return null
        val current = readVersionFile(id, pointer) ?: return null
        return Entry(
            id = id,
            name = current.script.name,
            description = current.script.description,
            enabled = current.script.enabled,
            trigger = current.script.trigger,
            actionCount = current.script.actions.size,
            versionCount = versionNumbers(id).size,
            currentVersion = pointer,
            updatedAt = current.savedAt,
        )
    }

    private fun readCurrent(id: String): ScriptSpec? {
        if (!isValidId(id)) return null
        val pointer = currentPointer(id) ?: return null
        return readVersionFile(id, pointer)?.script
    }

    /**
     * Reads the `current` pointer, falling back to the highest version present.
     *
     * The fallback matters: a hand-deleted or hand-edited pointer file must
     * degrade to "the newest version" instead of making the script disappear.
     */
    private fun currentPointer(id: String): Int? {
        val versions = versionNumbers(id)
        if (versions.isEmpty()) return null

        val raw = runCatching {
            File(dirOf(id), CURRENT_FILE).readText(Charsets.UTF_8).trim()
        }.getOrNull()

        val parsed = raw
            ?.removePrefix("v")
            ?.removeSuffix(".json")
            ?.trim()
            ?.toIntOrNull()

        return parsed?.takeIf { it in versions } ?: versions.last()
    }

    private fun versionNumbers(id: String): List<Int> = dirOf(id)
        .listFiles()
        .orEmpty()
        .asSequence()
        .mapNotNull { file ->
            VERSION_FILE_NAME.matchEntire(file.name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }
        .sorted()
        .toList()

    private fun readVersionFile(id: String, version: Int): ScriptVersionFile? {
        if (!isValidId(id) || version < 1) return null
        val file = versionFile(id, version)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(
                ScriptVersionFile.serializer(),
                file.readText(Charsets.UTF_8),
            )
        }.getOrNull()
    }

    private fun readVersionFiles(id: String): List<ScriptVersionFile> =
        versionNumbers(id).mapNotNull { readVersionFile(id, it) }

    // -----------------------------------------------------------------------
    // Internals — writing a script
    // -----------------------------------------------------------------------

    /**
     * Appends one version and moves the pointer. **The only write path.**
     *
     * The next slot is computed from the files actually present, and a slot that
     * somehow exists is skipped, so a concurrent or interrupted save can never
     * truncate an earlier version.
     */
    private fun writeVersion(id: String, script: ScriptSpec): ScriptSpec {
        val dir = dirOf(id)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw ScriptStoreException("无法创建脚本目录 ${dir.path}")
        }

        var slot = (versionNumbers(id).lastOrNull() ?: 0) + 1
        while (versionFile(id, slot).exists()) slot++

        val savedAt = System.currentTimeMillis()
        val stored = script.copy(id = id, version = slot)
        val record = ScriptVersionFile(
            version = slot,
            savedAt = savedAt,
            script = stored,
        )

        AtomicFiles.writeText(
            versionFile(id, slot),
            json.encodeToString(ScriptVersionFile.serializer(), record),
        )
        writeCurrentPointer(id, slot)
        return stored
    }

    private fun writeCurrentPointer(id: String, version: Int) {
        AtomicFiles.writeText(
            File(dirOf(id), CURRENT_FILE),
            pointerName(version) + "\n",
        )
    }

    /**
     * Rewrites `index.json`.
     *
     * Failures are swallowed **on purpose**: the script write already succeeded
     * and is on disk, and listing re-derives itself from the directories. Failing
     * the whole save because a summary file could not be written would be the
     * wrong trade.
     */
    private fun rewriteIndex() {
        runCatching {
            val entries = scan()
            val index = ScriptIndexFile(
                updatedAt = System.currentTimeMillis(),
                scripts = entries.map { entry ->
                    IndexEntry(
                        id = entry.id,
                        name = entry.name,
                        current = entry.currentVersion,
                        versions = entry.versionCount,
                        updatedAt = entry.updatedAt,
                        enabled = entry.enabled,
                        trigger = entry.trigger.name,
                    )
                },
            )
            AtomicFiles.writeText(
                indexFile,
                json.encodeToString(ScriptIndexFile.serializer(), index),
            )
        }
    }

    private fun stampText(stamp: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(stamp))

    // =======================================================================
    // Public data shapes
    // =======================================================================

    /** One library row: what the list screen and the Agent's `script_list` show. */
    data class Entry(
        val id: String,
        val name: String,
        val description: String = "",
        val enabled: Boolean = true,
        val trigger: ScriptTrigger = ScriptTrigger.Manual,
        val actionCount: Int = 0,
        val versionCount: Int = 0,
        val currentVersion: Int = 0,
        /** Save time of the current version, epoch millis. */
        val updatedAt: Long = 0L,
    )

    /** One row of a script's history. */
    data class VersionInfo(
        val version: Int,
        val savedAt: Long,
        val name: String,
        val actionCount: Int,
    )

    /** Outcome of [importBundle]. */
    data class ImportResult(
        val imported: Int,
        /** Scripts whose id collided and were imported under a fresh id. */
        val renamed: Int,
        val skipped: Int,
        val ids: List<String>,
    )

    companion object {
        /** Directory name of the library under the app's `filesDir`. */
        const val LIBRARY_DIR_NAME = "scripts"

        /** Directory name of the export area, a *sibling* of the library. */
        const val EXPORT_DIR_NAME = "script-exports"

        /** Marker written into every version file; checked by the reader. */
        const val FORMAT_VERSION_FILE = "regtrail.script.version"

        /** Marker written into an export bundle; checked by [importBundle]. */
        const val FORMAT_BUNDLE = "regtrail.scripts"

        const val INDEX_FILE = "index.json"
        const val CURRENT_FILE = "current"

        private val VERSION_FILE_NAME = Regex("""^v(\d{4,})\.json$""")

        /**
         * True when [id] is safe to use as a path segment.
         *
         * This is a **rejection** test, not a sanitiser: an id containing `/`,
         * `..`, a NUL or anything outside `[a-z0-9._-]` is refused outright, so
         * no caller can accidentally hand a traversal to [ScriptStore] and get a
         * silently rewritten path back.
         */
        fun isValidId(id: String): Boolean =
            id.length in 1..64 &&
                id.none { it == '/' || it == '\\' || it == '\u0000' } &&
                id != "." &&
                id != ".." &&
                id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } &&
                !id.startsWith(".")
    }
}

/** Thrown for anything the caller can reasonably show to the user verbatim. */
class ScriptStoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

// ---------------------------------------------------------------------------
// On-disk JSON shapes
// ---------------------------------------------------------------------------

/**
 * One file in a script's history.
 *
 * Self-contained by design: it names its own version and records what it was and
 * when, so an import can restore the numbering exactly instead of flattening
 * everything into one latest copy.
 */
@Serializable
data class ScriptVersionFile(
    val format: String = ScriptStore.FORMAT_VERSION_FILE,
    val version: Int = 1,
    val savedAt: Long = 0L,
    val script: ScriptSpec = ScriptSpec(id = "", name = ""),
)

/** One script inside an [ScriptBundle]. */
@Serializable
data class BundleScript(
    val id: String = "",
    val name: String = "",
    /** Version number `current` pointed at when the bundle was written. */
    val current: Int = 1,
    val versions: List<ScriptVersionFile> = emptyList(),
)

/**
 * The single file `exportBundle` writes and `importBundle` reads.
 *
 * [format] is **required** (no default) on purpose: with a default, any JSON
 * object at all would decode into a bundle — `{"hello":"world"}` would silently
 * "import" zero scripts and report success, which is exactly the kind of
 * half-truth an import must not tell. A missing `format` is now a decode error.
 * [formatVersion] exists so a future shape change is a clear rejection
 * ("不是 RegTrail 脚本包") instead of a half-decoded import.
 */
@Serializable
data class ScriptBundle(
    val format: String,
    val formatVersion: Int = 1,
    val exportedAt: Long = 0L,
    val scripts: List<BundleScript> = emptyList(),
)

/**
 * `index.json` — a summary, rebuilt from the directories on every save.
 *
 * Explicitly **not** a source of truth: it exists so a human (or `adb pull`)
 * can see the whole library without walking every script directory.
 */
@Serializable
data class ScriptIndexFile(
    val format: String = "regtrail.script.index",
    val updatedAt: Long = 0L,
    val scripts: List<IndexEntry> = emptyList(),
)

/** One row of [ScriptIndexFile]. */
@Serializable
data class IndexEntry(
    val id: String = "",
    val name: String = "",
    val current: Int = 0,
    val versions: Int = 0,
    val updatedAt: Long = 0L,
    val enabled: Boolean = true,
    val trigger: String = ScriptTrigger.Manual.name,
)
