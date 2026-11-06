package com.inkwell.notes.export.format

import com.inkwell.notes.export.backup.NonClosingOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A picture for the Word export. */
class DocxImage(val bytes: ByteArray, /** "png" or "jpeg". */ val extension: String, val widthPx: Int, val heightPx: Int)

/** Supplies pictures while the document is written; one is held in memory at a time. */
interface DocxImageSource {
    suspend fun asset(assetId: String): DocxImage?
    suspend fun page(pageIndex: Int): DocxImage?
}

/**
 * Writes a minimal, valid WordprocessingML (.docx) package with java.util.zip: content
 * types, package and document relationships, core/app properties, styles (Title,
 * Heading1-3, ListBullet, ListNumber, Quote, Code, Caption, Hyperlink, TableGrid), numbering
 * definitions, the document body and its pictures.
 */
class DocxWriter(private val images: DocxImageSource) {

    private val body = StringBuilder()
    private val rels = ArrayList<Rel>()
    private val numberedLists = LinkedHashMap<Int, Int>() // listId -> numId
    private var pictureCount = 0
    private var linkCount = 0

    private class Rel(val id: String, val type: String, val target: String, val external: Boolean = false)

    suspend fun write(doc: ExportDocument, out: OutputStream, createdAt: Long? = null, modifiedAt: Long? = null) {
        // Closing finishes the zip and frees its Deflater; the caller's stream is only flushed.
        ZipOutputStream(NonClosingOutputStream(out)).use { zip ->
            zip.setLevel(6)

            paragraph(style = "Title", runs = runsOf(doc.title.ifBlank { "Untitled" }))
            for (section in doc.sections) {
                if (section.heading != null) paragraph(style = "Heading1", runs = runsOf(section.heading))
                for (block in section.blocks) writeBlock(zip, block)
            }
            body.append(SECT_PR)

            zip.entry("[Content_Types].xml", contentTypes())
            zip.entry("_rels/.rels", PACKAGE_RELS)
            zip.entry("docProps/core.xml", coreProps(doc.title, createdAt, modifiedAt))
            zip.entry("docProps/app.xml", APP_PROPS)
            zip.entry("word/document.xml", DOC_OPEN + body + DOC_CLOSE)
            zip.entry("word/styles.xml", STYLES)
            zip.entry("word/numbering.xml", numbering())
            zip.entry("word/_rels/document.xml.rels", documentRels())
        }
    }

    // ------------------------------------------------------------------ Blocks

    private suspend fun writeBlock(zip: ZipOutputStream, block: ExportBlock) {
        when (block) {
            is ExportBlock.Heading -> paragraph(style = "Heading${block.level.coerceIn(1, 3)}", runs = block.runs)
            is ExportBlock.Paragraph -> paragraph(style = null, runs = block.runs)
            is ExportBlock.ListItem -> listItem(block)
            is ExportBlock.Quote -> paragraph(style = "Quote", runs = block.runs)
            is ExportBlock.Code -> paragraph(style = "Code", runs = listOf(InlineRun(block.text.trimEnd('\n'))))
            is ExportBlock.Table -> table(block.rows)
            is ExportBlock.Image -> {
                val img = images.asset(block.assetId)
                if (img != null) picture(zip, img, block.caption)
                if (block.caption.isNotBlank()) paragraph(style = "Caption", runs = runsOf(block.caption.trim()))
            }
            ExportBlock.Divider -> body.append(
                "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"BFBBB1\"/></w:pBdr></w:pPr></w:p>",
            )
            is ExportBlock.PageImage -> images.page(block.pageIndex)?.let { picture(zip, it, "Page ${block.pageIndex + 1}") }
            is ExportBlock.Timestamped -> {
                val runs = ArrayList<InlineRun>()
                runs += InlineRun("[${formatTimestamp(block.startMs)}] ", bold = true)
                if (!block.speaker.isNullOrBlank()) runs += InlineRun(block.speaker.trim() + ": ", bold = true)
                runs += InlineRun(block.text.trim())
                paragraph(style = null, runs = runs)
            }
        }
    }

    private fun paragraph(style: String?, runs: List<InlineRun>, extraPPr: String = "") {
        body.append("<w:p>")
        if (style != null || extraPPr.isNotEmpty()) {
            body.append("<w:pPr>")
            if (style != null) body.append("<w:pStyle w:val=\"").append(style).append("\"/>")
            body.append(extraPPr)
            body.append("</w:pPr>")
        }
        runs.forEach { appendRun(it) }
        body.append("</w:p>")
    }

    private fun listItem(item: ExportBlock.ListItem) {
        val level = item.depth.coerceIn(0, 8)
        when (item.kind) {
            ListKind.BULLET -> paragraph("ListBullet", item.runs, numPr(level, BULLET_NUM_ID))
            ListKind.NUMBERED -> {
                val numId = numberedLists.getOrPut(item.listId) { FIRST_NUMBERED_ID + numberedLists.size }
                paragraph("ListNumber", item.runs, numPr(level, numId))
            }
            ListKind.CHECKLIST -> {
                val box = InlineRun(if (item.checked) "☒ " else "☐ ")
                val runs = if (item.checked) item.runs.map { it.copy(strike = true) } else item.runs
                paragraph("ListParagraph", listOf(box) + runs, "<w:ind w:left=\"${360 + level * 360}\"/>")
            }
        }
    }

    private fun numPr(level: Int, numId: Int) =
        "<w:numPr><w:ilvl w:val=\"$level\"/><w:numId w:val=\"$numId\"/></w:numPr>"

    private fun appendRun(run: InlineRun) {
        if (run.text.isEmpty()) return
        val link = run.link?.trim()?.takeIf { isSafeLink(it) }
        if (link != null) {
            linkCount++
            val id = "rIdLink$linkCount"
            rels += Rel(id, REL_HYPERLINK, link, external = true)
            body.append("<w:hyperlink r:id=\"").append(id).append("\" w:history=\"1\">")
        }
        body.append("<w:r>")
        val rPr = StringBuilder()
        if (link != null) rPr.append("<w:rStyle w:val=\"Hyperlink\"/>")
        if (run.code) rPr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
        if (run.bold) rPr.append("<w:b/>")
        if (run.italic) rPr.append("<w:i/>")
        if (run.strike) rPr.append("<w:strike/>")
        if (run.underline && link == null) rPr.append("<w:u w:val=\"single\"/>")
        if (rPr.isNotEmpty()) body.append("<w:rPr>").append(rPr).append("</w:rPr>")
        appendText(run.text)
        body.append("</w:r>")
        if (link != null) body.append("</w:hyperlink>")
    }

    /** Text with tabs and line breaks as Word elements. */
    private fun appendText(text: String) {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                body.append("<w:t xml:space=\"preserve\">").append(XmlEscape.text(sb.toString())).append("</w:t>")
                sb.setLength(0)
            }
        }
        for (c in normalized) {
            when (c) {
                '\n' -> { flush(); body.append("<w:br/>") }
                '\t' -> { flush(); body.append("<w:tab/>") }
                else -> sb.append(c)
            }
        }
        flush()
    }

    private fun table(rows: List<List<String>>) {
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val colWidth = CONTENT_WIDTH_TWIPS / cols
        body.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"5000\" w:type=\"pct\"/>")
            .append("<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/></w:tblPr>")
        body.append("<w:tblGrid>")
        repeat(cols) { body.append("<w:gridCol w:w=\"").append(colWidth).append("\"/>") }
        body.append("</w:tblGrid>")
        rows.forEachIndexed { r, row ->
            body.append("<w:tr>")
            if (r == 0) body.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (c in 0 until cols) {
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(colWidth).append("\" w:type=\"dxa\"/></w:tcPr>")
                paragraph(style = null, runs = runsOf(row.getOrNull(c).orEmpty()).map { it.copy(bold = r == 0) })
                body.append("</w:tc>")
            }
            body.append("</w:tr>")
        }
        body.append("</w:tbl>")
        // Word needs a paragraph between consecutive tables and before the section end.
        body.append("<w:p/>")
    }

    private fun picture(zip: ZipOutputStream, img: DocxImage, description: String) {
        if (img.widthPx <= 0 || img.heightPx <= 0 || img.bytes.isEmpty()) return
        pictureCount++
        val n = pictureCount
        val ext = if (img.extension.equals("jpg", true) || img.extension.equals("jpeg", true)) "jpeg" else "png"
        val name = "image$n.$ext"
        zip.putNextEntry(ZipEntry("word/media/$name"))
        zip.write(img.bytes)
        zip.closeEntry()
        val relId = "rIdImg$n"
        rels += Rel(relId, REL_IMAGE, "media/$name")
        val (cx, cy) = pictureExtent(img.widthPx, img.heightPx)
        val descr = XmlEscape.attr(description)
        body.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>")
            .append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
            .append("<wp:extent cx=\"$cx\" cy=\"$cy\"/>")
            .append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
            .append("<wp:docPr id=\"$n\" name=\"Picture $n\" descr=\"$descr\"/>")
            .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
            .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            .append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr>")
            .append("<pic:blipFill><a:blip r:embed=\"$relId\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
            .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
            .append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
    }

    // ------------------------------------------------------------------ Parts

    private fun contentTypes(): String = XML_DECL +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
        "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
        "</Types>"

    private fun documentRels(): String {
        val sb = StringBuilder(XML_DECL)
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        sb.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        sb.append("<Relationship Id=\"rIdNumbering\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>")
        for (r in rels) {
            sb.append("<Relationship Id=\"").append(r.id).append("\" Type=\"").append(r.type)
                .append("\" Target=\"").append(XmlEscape.attr(r.target)).append('"')
            if (r.external) sb.append(" TargetMode=\"External\"")
            sb.append("/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun numbering(): String {
        val sb = StringBuilder(XML_DECL)
        sb.append("<w:numbering xmlns:w=\"$NS_W\">")
        // Bullets.
        sb.append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) {
            val glyph = BULLETS[l % BULLETS.size]
            sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"$glyph\"/>")
                .append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + l * 360}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        }
        sb.append("</w:abstractNum>")
        // Numbers: 1. a. i. repeating.
        sb.append("<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) {
            val fmt = NUMBER_FORMATS[l % NUMBER_FORMATS.size]
            sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$fmt\"/><w:lvlText w:val=\"%${l + 1}.\"/>")
                .append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + l * 360}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        }
        sb.append("</w:abstractNum>")
        sb.append("<w:num w:numId=\"$BULLET_NUM_ID\"><w:abstractNumId w:val=\"0\"/></w:num>")
        // One instance per numbered list so each list restarts at 1.
        for (numId in numberedLists.values) {
            sb.append("<w:num w:numId=\"$numId\"><w:abstractNumId w:val=\"1\"/>")
            for (l in 0..8) sb.append("<w:lvlOverride w:ilvl=\"$l\"><w:startOverride w:val=\"1\"/></w:lvlOverride>")
            sb.append("</w:num>")
        }
        sb.append("</w:numbering>")
        return sb.toString()
    }

    private fun coreProps(title: String, createdAt: Long?, modifiedAt: Long?): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        return XML_DECL +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${XmlEscape.text(title)}</dc:title><dc:creator>Inkwell</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">${fmt.format(Date(createdAt ?: now))}</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">${fmt.format(Date(modifiedAt ?: now))}</dcterms:modified>" +
            "</cp:coreProperties>"
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    companion object {
        const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        private const val REL_IMAGE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
        private const val REL_HYPERLINK = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
        private const val BULLET_NUM_ID = 1
        private const val FIRST_NUMBERED_ID = 2
        private val BULLETS = listOf("•", "◦", "▪")
        private val NUMBER_FORMATS = listOf("decimal", "lowerLetter", "lowerRoman")

        /** A4 with 1-inch margins. */
        private const val CONTENT_WIDTH_TWIPS = 11906 - 2 * 1440
        private const val EMU_PER_TWIP = 635L
        private const val MAX_WIDTH_EMU = CONTENT_WIDTH_TWIPS * EMU_PER_TWIP
        private const val MAX_HEIGHT_EMU = (16838 - 2 * 1440 - 720) * EMU_PER_TWIP
        private const val EMU_PER_PX = 9525L // at 96 dpi

        /** Natural size at 96 dpi, scaled down to fit the text block. */
        fun pictureExtent(widthPx: Int, heightPx: Int): Pair<Long, Long> {
            var cx = widthPx * EMU_PER_PX
            var cy = heightPx * EMU_PER_PX
            if (cx > MAX_WIDTH_EMU) {
                cy = cy * MAX_WIDTH_EMU / cx
                cx = MAX_WIDTH_EMU
            }
            if (cy > MAX_HEIGHT_EMU) {
                cx = cx * MAX_HEIGHT_EMU / cy
                cy = MAX_HEIGHT_EMU
            }
            return cx.coerceAtLeast(1) to cy.coerceAtLeast(1)
        }

        fun isSafeLink(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") || lower.startsWith("tel:")
        }

        private val DOC_OPEN = XML_DECL +
            "<w:document xmlns:w=\"$NS_W\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
            "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        private const val DOC_CLOSE = "</w:body></w:document>"
        private const val SECT_PR = "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            "</w:sectPr>"

        private val PACKAGE_RELS = XML_DECL +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>"

        private val APP_PROPS = XML_DECL +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">" +
            "<Application>Inkwell</Application></Properties>"

        private fun style(type: String, id: String, name: String, inner: String, default: Boolean = false, basedOn: String? = "Normal") =
            "<w:style w:type=\"$type\" w:styleId=\"$id\"" + (if (default) " w:default=\"1\"" else "") + ">" +
                "<w:name w:val=\"$name\"/>" + (if (basedOn != null) "<w:basedOn w:val=\"$basedOn\"/>" else "") +
                "<w:qFormat/>" + inner + "</w:style>"

        private val STYLES = XML_DECL +
            "<w:styles xmlns:w=\"$NS_W\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"140\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            style("paragraph", "Normal", "Normal", "", default = true, basedOn = null) +
            style("paragraph", "Title", "Title", "<w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1A1916\"/><w:sz w:val=\"48\"/><w:szCs w:val=\"48\"/></w:rPr>") +
            style("paragraph", "Heading1", "heading 1", "<w:pPr><w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1A1916\"/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr>") +
            style("paragraph", "Heading2", "heading 2", "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1A1916\"/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/></w:rPr>") +
            style("paragraph", "Heading3", "heading 3", "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"60\"/><w:outlineLvl w:val=\"2\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"57544C\"/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr>") +
            style("paragraph", "ListParagraph", "List Paragraph", "<w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\"/></w:pPr>") +
            style("paragraph", "ListBullet", "List Bullet", "<w:pPr><w:numPr><w:numId w:val=\"$BULLET_NUM_ID\"/></w:numPr><w:spacing w:after=\"60\"/></w:pPr>") +
            style("paragraph", "ListNumber", "List Number", "<w:pPr><w:spacing w:after=\"60\"/></w:pPr>") +
            style("paragraph", "Quote", "Quote", "<w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"8\" w:color=\"BFBBB1\"/></w:pBdr><w:ind w:left=\"360\"/></w:pPr><w:rPr><w:i/><w:color w:val=\"57544C\"/></w:rPr>") +
            style("paragraph", "Code", "Code", "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F3F2EE\"/><w:spacing w:after=\"140\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:sz w:val=\"19\"/><w:szCs w:val=\"19\"/></w:rPr>") +
            style("paragraph", "Caption", "caption", "<w:pPr><w:jc w:val=\"center\"/></w:pPr><w:rPr><w:i/><w:color w:val=\"57544C\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/></w:rPr>") +
            "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/><w:rPr><w:color w:val=\"2952CC\"/><w:u w:val=\"single\"/></w:rPr></w:style>" +
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/>" +
            "<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>" +
            "<w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBBB1\"/><w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBBB1\"/>" +
            "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBBB1\"/><w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBBB1\"/>" +
            "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBBB1\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBBB1\"/></w:tblBorders></w:tblPr></w:style>" +
            "</w:styles>"
    }
}
