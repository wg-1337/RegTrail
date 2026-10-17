package io.github.wg1337.regtrail.data

import io.github.wg1337.regtrail.shared.model.ScriptAction
import io.github.wg1337.regtrail.shared.model.ScriptSpec
import io.github.wg1337.regtrail.shared.model.ScriptTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The script library's contract, pinned down on the JVM.
 *
 * These tests exist because the two properties the feature is *for* are the two
 * that are easiest to break silently:
 *
 *  1. a save never destroys an earlier version (append-only history), and
 *  2. restoring an old version does not roll the history back.
 *
 * A file-per-version layout makes both properties observable from the outside —
 * the test asserts on the actual directory contents, not just on the API's
 * return values, because "the file is there" is what makes the export and any
 * future `adb pull` of the library truthful.
 */
class ScriptStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): ScriptStore = ScriptStore(File(temp.root, "scripts"))

    private fun action(
        kind: ScriptAction.Kind,
        className: String,
        member: String,
        value: String? = null,
    ) = ScriptAction(
        kind = kind,
        className = className,
        memberName = member,
        value = value,
        valueType = if (value == null) ScriptAction.ValueType.Auto else ScriptAction.ValueType.Boolean,
    )

    private fun spec(name: String, actions: List<ScriptAction>) = ScriptSpec(
        id = "",
        name = name,
        trigger = ScriptTrigger.Manual,
        actions = actions,
    )

    // -----------------------------------------------------------------------

    @Test
    fun createUpdateThreeVersionsThenRestoreFirst() {
        val store = store()

        val v1 = store.create(
            spec(
                name = "登录永远成功",
                actions = listOf(action(ScriptAction.Kind.Trace, "com.example.Login", "check")),
            ),
        )
        assertEquals("first save is v1", 1, v1.version)

        val v2 = store.update(
            v1.id,
            v1.copy(
                actions = v1.actions + action(
                    ScriptAction.Kind.SetReturn,
                    "com.example.Login",
                    "check",
                    "true",
                ),
            ),
        )
        assertEquals("a save bumps the version", 2, v2.version)
        assertEquals(2, v2.actions.size)

        val v3 = store.update(v1.id, v2.copy(name = "登录永远成功（改名）"))
        assertEquals(3, v3.version)

        // ---- history is on disk, one file per version ---------------------
        val dir = File(File(temp.root, "scripts"), v1.id)
        assertTrue("script dir exists", dir.isDirectory)
        val versionFiles = dir.listFiles().orEmpty()
            .map { it.name }
            .filter { it.startsWith("v") && it.endsWith(".json") }
            .sorted()
        assertEquals(listOf("v0001.json", "v0002.json", "v0003.json"), versionFiles)

        // The pointer is plain text so it survives a human reading the dir.
        assertEquals("v0003", File(dir, ScriptStore.CURRENT_FILE).readText(Charsets.UTF_8).trim())

        // index.json exists and counts three versions.
        val index = File(File(temp.root, "scripts"), ScriptStore.INDEX_FILE)
        assertTrue("index.json written", index.isFile)
        assertTrue(index.readText(Charsets.UTF_8).contains("\"versions\": 3"))

        // ---- the library API agrees --------------------------------------
        val entry = store.list().single()
        assertEquals(v1.id, entry.id)
        assertEquals(3, entry.versionCount)
        assertEquals(3, entry.currentVersion)
        assertEquals(2, entry.actionCount)

        // ---- restore v1 ---------------------------------------------------
        val restored = store.restore(v1.id, 1)
        assertEquals("restore appends a version", 4, restored.version)
        assertEquals("content is v1's", 1, restored.actions.size)
        assertEquals("v1's actions", v1.actions, restored.actions)
        assertEquals("name comes back too", "登录永远成功", restored.name)
        assertEquals("original trigger", v1.trigger, restored.trigger)

        // Nothing was overwritten: all four versions are still readable.
        assertEquals(4, store.versions(v1.id).size)
        assertEquals(1, store.loadVersion(v1.id, 1)?.version)
        assertEquals(2, store.loadVersion(v1.id, 2)?.actions?.size)
        assertEquals("登录永远成功（改名）", store.loadVersion(v1.id, 3)?.name)
        assertEquals("v0004", File(dir, ScriptStore.CURRENT_FILE).readText(Charsets.UTF_8).trim())

        // Current == the restored content.
        val current = store.load(v1.id)
        assertNotNull(current)
        assertEquals(v1.actions, current!!.actions)
        assertEquals(4, current.version)
    }

    @Test
    fun renameAndDuplicateCreateIndependentScripts() {
        val store = store()
        val created = store.create(spec("原始", listOf(action(ScriptAction.Kind.Trace, "A", "b"))))

        val renamed = store.rename(created.id, "改过的名字")
        assertEquals("改过的名字", renamed.name)
        assertEquals(2, renamed.version)

        val copy = store.duplicate(created.id)
        assertNotEquals(created.id, copy.id)
        // The copy starts from the *current* version, whose name is the renamed
        // one — that is what "duplicate" means, and asserting the pre-rename name
        // here would only encode a wrong expectation.
        assertEquals("改过的名字 副本", copy.name)
        assertEquals("a duplicate starts its own history", 1, copy.version)

        assertEquals(2, store.list().size)
        // The copy's history is independent: renaming the copy must not touch
        // the original's version count (create + rename = 2 versions there).
        store.rename(copy.id, "副本改名")
        assertEquals(2, store.versions(created.id).size)
        assertEquals(2, store.versions(copy.id).size)
    }

    @Test
    fun updateOfMissingScriptIsRejected() {
        val store = store()
        val failure = runCatching {
            store.update("s-does-not-exist", spec("x", emptyList()))
        }.exceptionOrNull()
        assertTrue(
            "updating an unknown id must fail instead of creating it: $failure",
            failure is ScriptStoreException,
        )
    }

    @Test
    fun exportAndImportRoundTripKeepsHistory() {
        val store = store()
        val created = store.create(spec("导出我", listOf(action(ScriptAction.Kind.Trace, "A", "b"))))
        store.update(created.id, created.copy(name = "导出我 v2"))

        val bundle = store.exportBundle(stamp = 1_700_000_000_000L)
        assertTrue("bundle written", bundle.isFile)
        assertTrue("bundle is inside the export dir", bundle.parentFile?.name == "script-exports")
        val text = bundle.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"format\": \"regtrail.scripts\""))

        // Import into a *fresh* library: the same id is free, so it is kept and
        // both versions come across.
        val other = ScriptStore(File(temp.newFolder("other"), "scripts"))
        val result = other.importBundle(text)
        assertEquals(1, result.imported)
        assertEquals(0, result.renamed)
        assertEquals(listOf(created.id), result.ids)
        assertEquals(2, other.versions(created.id).size)
        assertEquals("导出我 v2", other.load(created.id)?.name)

        // Importing the same bundle again must not clobber the local copy: the
        // id is taken, so a new one is allocated and both survive.
        val again = other.importBundle(text)
        assertEquals(1, again.imported)
        assertEquals(1, again.renamed)
        assertEquals(2, other.list().size)
        assertEquals(2, other.versions(created.id).size)
    }

    @Test
    fun importRejectsForeignJson() {
        val store = store()
        val failure = runCatching { store.importBundle("""{"hello":"world"}""") }
            .exceptionOrNull()
        assertTrue(
            "a JSON file that is not a RegTrail bundle must be refused: $failure",
            failure is ScriptStoreException,
        )
    }

    @Test
    fun deleteRemovesEveryVersion() {
        val store = store()
        val created = store.create(spec("待删", listOf(action(ScriptAction.Kind.Trace, "A", "b"))))
        store.update(created.id, created.copy(name = "待删 v2"))

        assertTrue(store.delete(created.id))
        assertFalse(File(File(temp.root, "scripts"), created.id).exists())
        assertTrue(store.list().isEmpty())
        assertNull(store.load(created.id))
    }

    @Test
    fun publishableReturnsCurrentVersionsOnly() {
        val store = store()
        val enabled = store.create(spec("启用", listOf(action(ScriptAction.Kind.Trace, "A", "b"))))
        val disabled = store.create(
            spec("停用", listOf(action(ScriptAction.Kind.Trace, "C", "d"))).copy(enabled = false),
        )
        store.update(enabled.id, enabled.copy(name = "启用 v2"))

        val published = store.publishable()
        assertEquals("disabled scripts are still published with their flag", 2, published.size)

        val enabledSpec = published.first { it.id == enabled.id }
        assertEquals("published version is the current one", 2, enabledSpec.version)
        assertEquals("启用 v2", enabledSpec.name)

        val disabledSpec = published.first { it.id == disabled.id }
        assertFalse(disabledSpec.enabled)
    }

    @Test
    fun idValidationRejectsTraversal() {
        assertTrue(ScriptStore.isValidId("s-3f9a12bc"))
        assertTrue(ScriptStore.isValidId("my_script.v2"))
        assertFalse(ScriptStore.isValidId(".."))
        assertFalse(ScriptStore.isValidId("../etc"))
        assertFalse(ScriptStore.isValidId("a/b"))
        assertFalse(ScriptStore.isValidId(".hidden"))
        assertFalse(ScriptStore.isValidId(""))
        assertFalse(ScriptStore.isValidId("a\u0000b"))
    }

    @Test
    fun malformedCurrentPointerFallsBackToNewestVersion() {
        val store = store()
        val created = store.create(spec("指针坏了", listOf(action(ScriptAction.Kind.Trace, "A", "b"))))
        store.update(created.id, created.copy(name = "指针坏了 v2"))

        // A hand-edited / truncated pointer must not make the script disappear.
        File(File(File(temp.root, "scripts"), created.id), ScriptStore.CURRENT_FILE)
            .writeText("garbage\n", Charsets.UTF_8)

        val loaded = store.load(created.id)
        assertNotNull("script still loads", loaded)
        assertEquals(2, loaded!!.version)
        assertEquals("指针坏了 v2", loaded.name)
    }
}
