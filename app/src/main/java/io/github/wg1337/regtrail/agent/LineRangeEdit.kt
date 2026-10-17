package io.github.wg1337.regtrail.agent

/**
 * The pure text half of the line-ranged file tools.
 *
 * ## Why this is a separate object
 * Everything here is a pure function of `(text, range, replacement)` — no
 * `File`, no Android, no root. That is what lets the exact behaviour the user
 * cares about ("replace a middle range", "append", "out-of-range is an error")
 * be pinned down by plain JVM unit tests instead of by watching a device.
 * `FileTools` adds paths, roots, atomic writes and a root fallback on top.
 *
 * ## Line model
 * A document is a list of logical lines plus one flag: whether the text ended
 * with a newline. Line numbers are **1-based and inclusive**, matching what a
 * user sees in an editor and what `sed -n '5,8p'` means.
 *
 * ```
 * "a\nb\nc\n"  → lines = [a, b, c],       trailingNewline = true
 * "a\nb\nc"    → lines = [a, b, c],       trailingNewline = false
 * ""           → lines = [],              trailingNewline = false   (0 lines)
 * "\n"         → lines = [""],            trailingNewline = true    (1 empty line)
 * ```
 *
 * Preserving the trailing-newline flag matters because a rewritten file that
 * silently gains or loses its final newline shows up as a spurious diff on the
 * next save — and the whole point of these tools is editing files in place.
 */
object LineRangeEdit {

    /** Thrown for a user-fixable mistake; the message is shown verbatim. */
    class RangeException(message: String) : Exception(message)

    /** A 1-based, inclusive line range. */
    data class Range(val startLine: Int, val endLine: Int)

    /** Logical lines plus the "did it end with a newline" bit. */
    data class Doc(val lines: List<String>, val trailingNewline: Boolean) {
        val size: Int get() = lines.size
    }

    /** Splits [text] into its logical lines. */
    fun doc(text: String): Doc {
        if (text.isEmpty()) return Doc(emptyList(), trailingNewline = false)
        val trailing = text.endsWith("\n")
        val body = if (trailing) text.dropLast(1) else text
        return Doc(body.split('\n'), trailing)
    }

    /** Renders [doc] back to text, restoring the trailing newline. */
    fun render(doc: Doc): String {
        val body = doc.lines.joinToString("\n")
        return if (doc.trailingNewline && doc.lines.isNotEmpty()) "$body\n" else body
    }

    /**
     * Rejects a range that cannot exist in a document of [total] lines.
     *
     * Out of range is an **error, never a clamp**: a model that asked to replace
     * lines 40..45 of a 12-line file has misunderstood the file, and silently
     * writing something else would hide that from it and from the user.
     */
    fun validate(total: Int, range: Range) {
        if (range.startLine < 1) {
            throw RangeException("startLine 从 1 开始，收到 ${range.startLine}")
        }
        if (range.endLine < range.startLine) {
            throw RangeException(
                "endLine(${range.endLine}) 不能小于 startLine(${range.startLine})",
            )
        }
        if (total == 0) {
            throw RangeException("文件是空的（0 行），没有可替换的行范围")
        }
        if (range.endLine > total) {
            throw RangeException(
                "行范围 ${range.startLine}..${range.endLine} 超出文件末尾：" +
                    "文件共 $total 行（1-based，含首尾）",
            )
        }
    }

    /**
     * Replaces [range] with [replacement], keeping everything outside it.
     *
     * An empty [replacement] deletes the range. The replacement's own trailing
     * newline is ignored — whether the file ends with a newline is the
     * document's property, not the snippet's.
     */
    fun replaceRange(text: String, range: Range, replacement: String): String {
        val target = doc(text)
        validate(target.size, range)
        val snippet = doc(replacement)

        val head = target.lines.subList(0, range.startLine - 1)
        val tail = target.lines.subList(range.endLine, target.size)

        val lines = ArrayList<String>(head.size + snippet.size + tail.size)
        lines.addAll(head)
        lines.addAll(snippet.lines)
        lines.addAll(tail)
        return render(Doc(lines, target.trailingNewline))
    }

    /**
     * Appends [addition] after the last line.
     *
     * The result always ends with exactly one newline (when [addition] is not
     * empty): appending to a file whose last line had no newline would otherwise
     * glue the new text onto it and produce one long corrupted line.
     */
    fun append(text: String, addition: String): String {
        if (addition.isEmpty()) return text
        val base = if (text.isEmpty() || text.endsWith("\n")) text else "$text\n"
        val tail = if (addition.endsWith("\n")) addition else "$addition\n"
        return base + tail
    }

    /**
     * Renders [text] with line numbers, optionally only [range].
     *
     * Numbers are right-aligned to a stable width so the `|` separators line up,
     * and the cap exists because a 50 000-line dump would cost the model more
     * context than the answer is worth.
     */
    fun numbered(text: String, range: Range? = null, maxLines: Int = 2000): String {
        val target = doc(text)
        if (target.size == 0) return "（空文件，0 行）"

        val from: Int
        val to: Int
        if (range == null) {
            from = 1
            to = target.size
        } else {
            validate(target.size, range)
            from = range.startLine
            to = range.endLine
        }

        val width = target.size.toString().length.coerceAtLeast(4)
        val builder = StringBuilder()
        var written = 0
        for (line in from..to) {
            if (written >= maxLines) {
                builder.append("…（已截断：本次最多显示 ").append(maxLines).append(" 行）")
                break
            }
            val number = line.toString().padStart(width)
            builder.append(number).append("| ").append(target.lines[line - 1]).append('\n')
            written++
        }
        return builder.toString().trimEnd('\n')
    }
}
