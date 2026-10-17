package io.github.wg1337.regtrail.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The arithmetic behind `read_file` / `write_file`, pinned down without a device.
 *
 * Every test here is a case a user would actually hit: replace a middle range,
 * append, delete a range, and — the important one — ask for a range that does not
 * exist. The out-of-range cases assert the *error*, because clamping silently is
 * how a model (and the user reading its report) ends up believing a file was
 * edited when a different part of it was.
 */
class LineRangeEditTest {

    private fun range(start: Int, end: Int) = LineRangeEdit.Range(start, end)

    // -----------------------------------------------------------------------
    // replace
    // -----------------------------------------------------------------------

    @Test
    fun replacesOnlyTheMiddleRange() {
        val original = "line1\nline2\nline3\nline4\nline5\n"
        val result = LineRangeEdit.replaceRange(original, range(2, 3), "NEW-A\nNEW-B")
        assertEquals("line1\nNEW-A\nNEW-B\nline4\nline5\n", result)
    }

    @Test
    fun replacingWithMoreLinesThanTheRangeKeepsTheRest() {
        val original = "a\nb\nc\n"
        val result = LineRangeEdit.replaceRange(original, range(2, 2), "b1\nb2\nb3")
        assertEquals("a\nb1\nb2\nb3\nc\n", result)
    }

    @Test
    fun replacingWithFewerLinesShrinksTheFile() {
        val original = "a\nb\nc\nd\n"
        val result = LineRangeEdit.replaceRange(original, range(2, 4), "single")
        assertEquals("a\nsingle\n", result)
    }

    @Test
    fun emptyReplacementDeletesTheRange() {
        val original = "keep1\ndrop\ndrop\nkeep2\n"
        val result = LineRangeEdit.replaceRange(original, range(2, 3), "")
        assertEquals("keep1\nkeep2\n", result)
    }

    @Test
    fun replacingTheLastLineKeepsTheTrailingNewline() {
        val original = "a\nb\n"
        assertEquals("a\nc\n", LineRangeEdit.replaceRange(original, range(2, 2), "c"))
    }

    @Test
    fun replacingWithoutATrailingNewlineDoesNotInventOne() {
        val original = "a\nb"
        assertEquals("a\nc", LineRangeEdit.replaceRange(original, range(2, 2), "c"))
    }

    @Test
    fun replacingTheWholeFileWorks() {
        val original = "1\n2\n3"
        assertEquals("x", LineRangeEdit.replaceRange(original, range(1, 3), "x"))
    }

    // -----------------------------------------------------------------------
    // out of range — must be an error, never a clamp
    // -----------------------------------------------------------------------

    @Test
    fun rangePastTheEndIsRejected() {
        try {
            LineRangeEdit.replaceRange("a\nb\nc\n", range(4, 5), "x")
            fail("a range starting past the last line must throw")
        } catch (e: LineRangeEdit.RangeException) {
            assertTrue(
                "message names the real line count: ${e.message}",
                e.message!!.contains("共 3 行"),
            )
        }
    }

    @Test
    fun rangeEndingPastTheEndIsRejected() {
        try {
            LineRangeEdit.replaceRange("a\nb\n", range(1, 99), "x")
            fail("endLine past the end must throw")
        } catch (e: LineRangeEdit.RangeException) {
            assertTrue(e.message!!.contains("超出文件末尾"))
        }
    }

    @Test
    fun zeroStartLineIsRejected() {
        try {
            LineRangeEdit.replaceRange("a\n", range(0, 1), "x")
            fail("line numbers are 1-based, so 0 must throw")
        } catch (e: LineRangeEdit.RangeException) {
            assertTrue(e.message!!.contains("从 1 开始"))
        }
    }

    @Test
    fun invertedRangeIsRejected() {
        try {
            LineRangeEdit.replaceRange("a\nb\n", range(2, 1), "x")
            fail("endLine < startLine must throw")
        } catch (e: LineRangeEdit.RangeException) {
            assertTrue(e.message!!.contains("不能小于"))
        }
    }

    @Test
    fun anyRangeInAnEmptyFileIsRejected() {
        try {
            LineRangeEdit.replaceRange("", range(1, 1), "x")
            fail("an empty file has no lines to replace")
        } catch (e: LineRangeEdit.RangeException) {
            assertTrue(e.message!!.contains("0 行"))
        }
    }

    // -----------------------------------------------------------------------
    // append
    // -----------------------------------------------------------------------

    @Test
    fun appendAddsAfterTheLastLine() {
        assertEquals("a\nb\nc\n", LineRangeEdit.append("a\nb\n", "c"))
    }

    @Test
    fun appendDoesNotGlueOntoALineWithoutNewline() {
        assertEquals(
            "the missing final newline must be added, not merged",
            "a\nb\nc\n",
            LineRangeEdit.append("a\nb", "c"),
        )
    }

    @Test
    fun appendKeepsMultipleNewLines() {
        assertEquals("a\nb1\nb2\n", LineRangeEdit.append("a\n", "b1\nb2"))
    }

    @Test
    fun appendToAnEmptyFileIsJustTheText() {
        assertEquals("hello\n", LineRangeEdit.append("", "hello"))
    }

    @Test
    fun appendNothingChangesNothing() {
        assertEquals("a\n", LineRangeEdit.append("a\n", ""))
    }

    // -----------------------------------------------------------------------
    // read (numbered) + document round trip
    // -----------------------------------------------------------------------

    @Test
    fun numberedOutputCarriesTheRealLineNumbers() {
        val text = "alpha\nbeta\ngamma\n"
        val numbered = LineRangeEdit.numbered(text)
        val lines = numbered.split("\n")
        assertEquals(3, lines.size)
        assertTrue("1-based and padded: ${lines[0]}", lines[0].startsWith("   1| alpha"))
        assertTrue(lines[1].startsWith("   2| beta"))
        assertTrue(lines[2].startsWith("   3| gamma"))
    }

    @Test
    fun numberedRangeOnlyShowsTheRequestedLines() {
        val numbered = LineRangeEdit.numbered("a\nb\nc\nd\n", range(2, 3))
        val lines = numbered.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("b"))
        assertTrue(lines[1].endsWith("c"))
    }

    @Test
    fun numberedEmptyFileSaysSo() {
        assertEquals("（空文件，0 行）", LineRangeEdit.numbered(""))
    }

    @Test
    fun docRoundTripsEveryInterestingShape() {
        listOf(
            "",
            "a",
            "a\n",
            "a\nb",
            "a\nb\n",
            "\n",
            "\n\n",
            "a\n\nb\n",
            "  空格行  \n中文\n",
        ).forEach { text ->
            assertEquals("round trip of ${text.replace("\n", "\\n")}", text, LineRangeEdit.render(LineRangeEdit.doc(text)))
        }
    }

    @Test
    fun docLineCountsMatchWhatAnEditorShows() {
        assertEquals(0, LineRangeEdit.doc("").size)
        assertEquals(1, LineRangeEdit.doc("a").size)
        assertEquals(1, LineRangeEdit.doc("a\n").size)
        assertEquals(2, LineRangeEdit.doc("a\nb").size)
        assertEquals(3, LineRangeEdit.doc("a\nb\nc\n").size)
    }
}
