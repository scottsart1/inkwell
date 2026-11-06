package com.inkwell.notes.export.format

/**
 * Renders an [ExportDocument] as CommonMark + GFM (tables, task lists, strikethrough).
 * The document title is the only H1; sections are H2 and typed sub-headings H3/H4.
 */
object MarkdownWriter {

    fun write(doc: ExportDocument): String {
        val out = StringBuilder()
        out.append("# ").append(escapeInline(doc.title.ifBlank { "Untitled" }).replace('\n', ' ')).append("\n")
        for (section in doc.sections) {
            if (section.heading == null && section.blocks.isEmpty()) continue
            if (section.heading != null) {
                out.append("\n## ").append(escapeInline(section.heading).replace('\n', ' ')).append("\n")
            }
            writeBlocks(out, section.blocks)
        }
        return out.toString().trimEnd() + "\n"
    }

    private fun writeBlocks(out: StringBuilder, blocks: List<ExportBlock>) {
        var previous: ExportBlock? = null
        for (block in blocks) {
            // Consecutive items of one list stay tight; everything else is separated by a blank line.
            val tight = block is ExportBlock.ListItem && previous is ExportBlock.ListItem && previous.listId == block.listId
            if (!tight) out.append("\n")
            when (block) {
                is ExportBlock.Heading -> {
                    val hashes = "#".repeat((block.level + 1).coerceIn(2, 6))
                    out.append(hashes).append(' ').append(inline(block.runs).replace('\n', ' ')).append("\n")
                }
                is ExportBlock.Paragraph -> out.append(hardBreaks(inline(block.runs))).append("\n")
                is ExportBlock.ListItem -> {
                    val indent = "    ".repeat(block.depth)
                    val marker = when (block.kind) {
                        ListKind.BULLET -> "- "
                        ListKind.NUMBERED -> "${block.number}. "
                        ListKind.CHECKLIST -> if (block.checked) "- [x] " else "- [ ] "
                    }
                    val body = inline(block.runs).replace("\n", "\n$indent" + " ".repeat(marker.length))
                    out.append(indent).append(marker).append(body).append("\n")
                }
                is ExportBlock.Quote -> {
                    inline(block.runs).split('\n').forEach { line -> out.append("> ").append(line).append("\n") }
                }
                is ExportBlock.Code -> {
                    val fence = fenceFor(block.text)
                    out.append(fence).append(block.language?.filter { !it.isWhitespace() && it != '`' }.orEmpty()).append("\n")
                    out.append(block.text.trimEnd('\n')).append("\n")
                    out.append(fence).append("\n")
                }
                is ExportBlock.Table -> writeTable(out, block.rows)
                is ExportBlock.Image -> {
                    // The asset is not exported next to the .md, so a link would never resolve: name the image instead.
                    val caption = block.caption.replace('\n', ' ').trim()
                    out.append('*').append(escapeInline(if (caption.isEmpty()) "[Image]" else "[Image: $caption]")).append("*\n")
                }
                ExportBlock.Divider -> out.append("---\n")
                is ExportBlock.PageImage -> Unit // Word only.
                is ExportBlock.Timestamped -> {
                    out.append('[').append(formatTimestamp(block.startMs)).append("] ")
                    if (!block.speaker.isNullOrBlank()) out.append("**").append(escapeInline(block.speaker.trim())).append(":** ")
                    out.append(escapeInline(block.text.trim()).replace('\n', ' ')).append("\n")
                }
            }
            previous = block
        }
    }

    private fun writeTable(out: StringBuilder, rows: List<List<String>>) {
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        fun row(cells: List<String>) {
            out.append('|')
            for (i in 0 until cols) {
                val cell = cells.getOrNull(i).orEmpty().replace("\r", "").replace('\n', ' ').trim()
                out.append(' ').append(escapeInline(cell)).append(" |")
            }
            out.append('\n')
        }
        row(rows.first())
        out.append('|').append(" --- |".repeat(cols)).append('\n')
        rows.drop(1).forEach { row(it) }
    }

    private fun fenceFor(code: String): String {
        var longest = 0
        var run = 0
        for (c in code) {
            if (c == '`') { run++; if (run > longest) longest = run } else run = 0
        }
        return "`".repeat(maxOf(3, longest + 1))
    }

    /** Lines inside one paragraph keep their breaks (backslash hard break). */
    private fun hardBreaks(s: String): String = s.replace("\n", "\\\n")

    /** Inline runs with emphasis markers kept outside surrounding whitespace. */
    fun inline(runs: List<InlineRun>): String {
        val sb = StringBuilder()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            if (r.code) {
                sb.append(codeSpan(r.text))
                continue
            }
            val core = r.text.trim()
            if (core.isEmpty()) {
                sb.append(escapeInline(r.text))
                continue
            }
            val lead = r.text.substring(0, r.text.indexOf(core))
            val trail = r.text.substring(r.text.indexOf(core) + core.length)
            var body = escapeInline(core)
            if (r.strike) body = "~~$body~~"
            if (r.italic) body = "*$body*"
            if (r.bold) body = "**$body**"
            if (r.underline && !r.bold && !r.italic && r.link == null) body = "<u>$body</u>"
            if (r.link != null) body = "[$body](${linkTarget(r.link)})"
            sb.append(escapeInline(lead)).append(body).append(escapeInline(trail))
        }
        return escapeLineStarts(sb.toString())
    }

    private fun codeSpan(text: String): String {
        val clean = text.replace('\n', ' ')
        var longest = 0
        var run = 0
        for (c in clean) {
            if (c == '`') { run++; if (run > longest) longest = run } else run = 0
        }
        val ticks = "`".repeat(longest + 1)
        val pad = if (clean.startsWith('`') || clean.endsWith('`') || clean.startsWith(' ') && clean.endsWith(' ')) " " else ""
        return "$ticks$pad$clean$pad$ticks"
    }

    private fun linkTarget(url: String): String {
        val u = url.trim()
        return if (u.any { it.isWhitespace() || it == '(' || it == ')' || it == '<' || it == '>' }) {
            "<" + u.replace("<", "%3C").replace(">", "%3E").replace("\n", "") + ">"
        } else u
    }

    private val INLINE_SPECIAL = setOf('\\', '`', '*', '_', '[', ']', '<', '>', '~', '|')

    /** Escapes characters that would start inline Markdown syntax. */
    fun escapeInline(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (c in INLINE_SPECIAL) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private val BLOCK_START = Regex("""(?m)^(\s{0,3})([#+\-=]|\d{1,9}[.)])(?=\s|$)""")
    private val LEADING_WHITESPACE = Regex("""(?m)^[ \t]+""")

    /**
     * Escapes text that would otherwise start a heading, list or rule at a line start, and keeps
     * indentation of four or more columns (a tab counts as four) from starting an indented code
     * block by writing it as non-breaking spaces.
     */
    private fun escapeLineStarts(s: String): String {
        val indented = LEADING_WHITESPACE.replace(s) { m ->
            val ws = m.value
            val columns = ws.sumOf { if (it == '\t') 4 else 1 }
            if (columns < 4) ws else ws.replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;").replace(" ", "&nbsp;")
        }
        return BLOCK_START.replace(indented) { m ->
            val lead = m.groupValues[1]
            val token = m.groupValues[2]
            if (token.first().isDigit()) lead + token.dropLast(1) + "\\" + token.last() else "$lead\\$token"
        }
    }
}
