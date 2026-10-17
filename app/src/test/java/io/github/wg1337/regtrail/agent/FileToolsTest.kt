package io.github.wg1337.regtrail.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The sandbox half of `read_file` / `write_file`.
 *
 * The point of these tests is the *rejection* behaviour: a path that leaves the
 * allowed roots must produce an error naming the roots, not a silently rewritten
 * path. "It wrote somewhere else and said it worked" is the failure mode that
 * makes a file-editing tool dangerous, so it is the one pinned down here.
 */
class FileToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File

    private fun tools(): FileTools {
        root = temp.newFolder("workspace")
        return FileTools(roots = listOf(root), defaultRoot = root)
    }

    @Test
    fun relativePathResolvesInsideTheDefaultRoot() {
        val file = tools().resolve("notes/todo.txt")
        assertTrue(file.path.startsWith(root.canonicalPath + File.separator))
        assertTrue(file.path.endsWith("todo.txt"))
    }

    @Test
    fun absolutePathInsideTheRootIsAccepted() {
        val tools = tools()
        val absolute = File(root, "a/b.txt").absolutePath
        assertEquals(
            File(absolute).canonicalPath,
            tools.resolve(absolute).path,
        )
    }

    @Test
    fun parentTraversalIsRejectedNotNormalised() {
        val tools = tools()
        try {
            tools.resolve("../outside.txt")
            fail("\"..\" must be refused outright")
        } catch (e: FileTools.FileToolException) {
            assertTrue(
                "error explains why: ${e.message}",
                e.message!!.contains("..") && e.message!!.contains("允许的根目录"),
            )
        }
    }

    @Test
    fun traversalInTheMiddleIsRejectedToo() {
        val tools = tools()
        try {
            tools.resolve("a/../../escape.txt")
            fail("an embedded \"..\" must be refused as well")
        } catch (e: FileTools.FileToolException) {
            assertTrue(e.message!!.contains(".."))
        }
    }

    @Test
    fun absolutePathOutsideEveryRootIsRejected() {
        val tools = tools()
        try {
            // A real, readable path that is simply not one of the roots. On
            // Windows this is still absolute; on Linux it is the temp parent.
            tools.resolve(temp.root.parentFile!!.absolutePath + "/elsewhere.txt")
            fail("a path outside the roots must be refused")
        } catch (e: FileTools.FileToolException) {
            assertTrue(e.message!!.contains("不在允许的根目录内"))
        }
    }

    @Test
    fun systemPathsAreRejected() {
        val tools = tools()
        listOf("/system/build.prop", "/data/data/com.other/shared_prefs/x.xml").forEach { path ->
            try {
                tools.resolve(path)
                fail("$path must be refused")
            } catch (e: FileTools.FileToolException) {
                assertTrue(e.message!!.contains("不在允许的根目录内"))
            }
        }
    }

    @Test
    fun emptyPathIsRejected() {
        try {
            tools().resolve("   ")
            fail("an empty path must be refused")
        } catch (e: FileTools.FileToolException) {
            assertTrue(e.message!!.contains("不能为空"))
        }
    }

    @Test
    fun singleQuoteIsRejectedBecauseItBreaksRootQuoting() {
        try {
            tools().resolve("a'b.txt")
            fail("a single quote must be refused: it would escape the root shell quoting")
        } catch (e: FileTools.FileToolException) {
            assertTrue(e.message!!.contains("单引号"))
        }
    }

    @Test
    fun dotDotOnlyNamesOneIsNotAcceptedAsADirectory() {
        // A plain "." segment is fine (it stays inside), but ".." is not; this
        // documents the difference so a future "sanitise it instead" refactor
        // fails a test rather than shipping.
        val tools = tools()
        val resolved = tools.resolve("./ok.txt")
        assertFalse(resolved.path.contains(".."))
    }
}
