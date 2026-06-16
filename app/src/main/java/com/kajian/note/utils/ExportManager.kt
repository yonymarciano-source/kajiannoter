package com.kajian.note.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.core.content.FileProvider
import com.kajian.note.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExportManager {

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun exportPdf(
        context: Context,
        note: Note,
        summary: String?
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val file = getOutputFile(context, note, "pdf")
            generatePdf(note, summary, file)
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportDocx(
        context: Context,
        note: Note,
        summary: String?
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val file = getOutputFile(context, note, "docx")
            generateDocx(note, summary, file)
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Export Translate ──────────────────────────────────────────────────────

    suspend fun exportTranslatePdf(
        context: Context,
        note: Note,
        translatedText: String,
        targetLang: String  // "id" atau "en"
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val suffix = if (targetLang == "en") "translate_en" else "translate_id"
            val file = getOutputFile(context, note, "pdf", suffix)
            generateTranslatePdf(note, translatedText, targetLang, file)
            Result.success(file)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun exportTranslateDocx(
        context: Context,
        note: Note,
        translatedText: String,
        targetLang: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val suffix = if (targetLang == "en") "translate_en" else "translate_id"
            val file = getOutputFile(context, note, "docx", suffix)
            generateTranslateDocx(note, translatedText, targetLang, file)
            Result.success(file)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Export Poin Kunci ─────────────────────────────────────────────────────

    suspend fun exportPoinKunciPdf(
        context: Context,
        note: Note,
        poinKunciText: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val file = getOutputFile(context, note, "pdf", "poinkunci")
            generatePoinKunciPdf(note, poinKunciText, file)
            Result.success(file)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun exportPoinKunciDocx(
        context: Context,
        note: Note,
        poinKunciText: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val file = getOutputFile(context, note, "docx", "poinkunci")
            generatePoinKunciDocx(note, poinKunciText, file)
            Result.success(file)
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getFileUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // ── PDF Generator ─────────────────────────────────────────────────────────

    private fun generatePdf(note: Note, summary: String?, outFile: File) {
        val doc = PdfDocument()
        val pageWidth  = 595
        val pageHeight = 842
        val margin     = 56f

        val colorPrimary = Color.parseColor("#1A7A40")   // green gelap — tetap keliatan di putih
        val colorDark    = Color.WHITE
        val colorSurface = Color.parseColor("#F5F5F5")
        val colorText    = Color.parseColor("#1A1A1A")
        val colorMuted   = Color.parseColor("#666666")

        val paintBg     = Paint().apply { color = colorDark;    style = Paint.Style.FILL }
        val paintAccent = Paint().apply { color = colorPrimary; style = Paint.Style.FILL }
        val paintTitle  = Paint().apply { color = Color.parseColor("#1A1A1A"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val paintMeta   = Paint().apply { color = colorMuted;   textSize = 11f; isAntiAlias = true }
        val paintHead   = Paint().apply { color = colorPrimary; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val paintBody   = Paint().apply { color = colorText;    textSize = 12f; isAntiAlias = true }
        val paintSmall  = Paint().apply { color = colorMuted;   textSize = 10f; isAntiAlias = true }

        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
        var page     = doc.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var y = margin

        fun drawBg() = canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), paintBg)

        fun newPage() {
            doc.finishPage(page)
            pageNum++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            page   = doc.startPage(pageInfo)
            canvas = page.canvas
            y      = margin
            drawBg()
            canvas.drawRect(0f, 0f, 6f, pageHeight.toFloat(), paintAccent)
        }

        fun checkBreak(needed: Float) { if (y + needed > pageHeight - margin) newPage() }

        fun drawWrapped(text: String, paint: Paint, x: Float, maxW: Float) {
            if (text.isBlank()) return
            val words = text.split(" ")
            val sb    = StringBuilder()
            for (word in words) {
                val test = if (sb.isEmpty()) word else "$sb $word"
                if (paint.measureText(test) > maxW) {
                    checkBreak(paint.textSize + 4f)
                    canvas.drawText(sb.toString(), x, y, paint)
                    y += paint.textSize + 4f
                    sb.clear(); sb.append(word)
                } else { sb.clear(); sb.append(test) }
            }
            if (sb.isNotEmpty()) {
                checkBreak(paint.textSize + 4f)
                canvas.drawText(sb.toString(), x, y, paint)
                y += paint.textSize + 4f
            }
        }

        // Draw first page
        drawBg()
        canvas.drawRect(0f, 0f, 6f, pageHeight.toFloat(), paintAccent)

        canvas.drawText("KajianNote", margin, y, paintSmall); y += 20f
        drawWrapped(note.title.ifBlank { "Catatan Kajian" }, paintTitle, margin, pageWidth - margin * 2)
        y += 6f
        canvas.drawText("${note.getFormattedDate()}  ·  ${note.getLanguageLabel()}  ·  ${note.getFormattedDuration()}  ·  ${note.wordCount} kata", margin, y, paintMeta)
        y += 20f
        canvas.drawRect(margin, y, pageWidth - margin, y + 1f, paintAccent); y += 16f

        if (!summary.isNullOrBlank()) {
            checkBreak(24f)
            canvas.drawText("✦ RINGKASAN", margin, y, paintHead); y += 16f
            for (line in summary.lines()) {
                checkBreak(paintBody.textSize + 6f)
                drawWrapped(line, paintBody, margin + 8f, pageWidth - margin * 2 - 8f)
                y += 4f
            }
            y += 10f
            canvas.drawRect(margin, y, pageWidth - margin, y + 1f, Paint().apply { color = colorSurface; style = Paint.Style.FILL })
            y += 14f
        }

        checkBreak(24f)
        canvas.drawText("✦ TRANSKRIPSI", margin, y, paintHead); y += 16f
        // Pecah transkripsi jadi paragraf yang readable
        val paragraphs = splitTranscriptToParagraphs(note.plainText)
        for (para in paragraphs) {
            if (para.isBlank()) { y += 10f; continue }
            // Speaker label ([Speaker X]:) → bold
            if (para.trimStart().startsWith("[Speaker") || para.trimStart().startsWith("[speaker")) {
                val colonIdx = para.indexOf("]:")
                if (colonIdx > 0) {
                    val label = para.substring(0, colonIdx + 2)
                    val rest  = para.substring(colonIdx + 2).trim()
                    checkBreak(paintHead.textSize + 6f)
                    drawWrapped(label, paintHead, margin, pageWidth - margin * 2)
                    if (rest.isNotBlank()) {
                        drawWrapped(rest, paintBody, margin + 8f, pageWidth - margin * 2 - 8f)
                    }
                } else {
                    drawWrapped(para, paintBody, margin, pageWidth - margin * 2)
                }
            } else {
                drawWrapped(para, paintBody, margin, pageWidth - margin * 2)
            }
            y += 8f  // jeda antar paragraf
        }

        // Footer
        val footerY = pageHeight - margin + 10f
        canvas.drawText("Diekspor dari KajianNote  ·  ${note.getFormattedDate()}", margin, footerY, paintSmall)

        doc.finishPage(page)
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
    }

    // ── DOCX Generator (pure XML/ZIP, no POI needed) ──────────────────────────

    private fun generateDocx(note: Note, summary: String?, outFile: File) {
        FileOutputStream(outFile).use { fos ->
            ZipOutputStream(fos).use { zip ->

                // [Content_Types].xml
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write(contentTypes().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // _rels/.rels
                zip.putNextEntry(ZipEntry("_rels/.rels"))
                zip.write(rootRels().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // word/_rels/document.xml.rels
                zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                zip.write(documentRels().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // word/styles.xml
                zip.putNextEntry(ZipEntry("word/styles.xml"))
                zip.write(styles().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // word/document.xml
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(buildDocument(note, summary).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun buildDocument(note: Note, summary: String?): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas"
    xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <w:body>
""")
        // Title
        sb.append(para(note.title.ifBlank { "Catatan Kajian" }, style = "Title", bold = true, size = 36))

        // Meta
        sb.append(para("${note.getFormattedDate()}  ·  ${note.detectedLanguage}  ·  ${note.getFormattedDuration()}  ·  ${note.wordCount} kata",
            size = 20, color = "888888"))

        sb.append(para("")) // spacer

        // Summary
        if (!summary.isNullOrBlank()) {
            sb.append(para("RINGKASAN", bold = true, size = 24, color = "1A7A40"))
            for (line in summary.lines()) {
                if (line.isNotBlank()) sb.append(para(line, size = 22))
            }
            sb.append(para("")) // spacer
        }

        // Transcript — pecah per paragraf
        sb.append(para("TRANSKRIPSI", bold = true, size = 24, color = "1A7A40"))
        val transcriptParas = splitTranscriptToParagraphs(note.plainText)
        for (p in transcriptParas) {
            if (p.isBlank()) {
                sb.append(para("", size = 22))
            } else if (p.trimStart().startsWith("[Speaker") || p.trimStart().startsWith("[speaker")) {
                val colonIdx = p.indexOf("]:")
                if (colonIdx > 0) {
                    val label = p.substring(0, colonIdx + 2)
                    val rest  = p.substring(colonIdx + 2).trim()
                    sb.append(para(label, bold = true, size = 22, color = "1A7A40"))
                    if (rest.isNotBlank()) sb.append(para(rest, size = 22))
                } else {
                    sb.append(para(p, bold = true, size = 22, color = "1A7A40"))
                }
            } else {
                sb.append(para(p, size = 22))
            }
        }

        // Footer
        sb.append(para(""))
        sb.append(para("Diekspor dari KajianNote  ·  ${note.getFormattedDate()}", size = 18, color = "888888"))

        sb.append("""
  </w:body>
</w:document>""")
        return sb.toString()
    }

    private fun para(
        text: String,
        style: String? = null,
        bold: Boolean = false,
        size: Int = 22,          // half-points, 22 = 11pt
        color: String = "1A1A1A"
    ): String {
        val styleXml = if (style != null) "<w:pStyle w:val=\"$style\"/>" else ""
        val boldXml  = if (bold) "<w:b/><w:bCs/>" else ""
        val safeText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        return """    <w:p>
      <w:pPr><w:pStyle w:val="Normal"/>$styleXml<w:spacing w:after="80"/></w:pPr>
      <w:r>
        <w:rPr>$boldXml<w:sz w:val="$size"/><w:szCs w:val="$size"/><w:color w:val="$color"/></w:rPr>
        <w:t xml:space="preserve">$safeText</w:t>
      </w:r>
    </w:p>
"""
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml"  ContentType="application/xml"/>
  <Override PartName="/word/document.xml"
    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml"
    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    Target="word/document.xml"/>
</Relationships>"""

    private fun documentRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
    Target="styles.xml"/>
</Relationships>"""

    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:style w:type="paragraph" w:styleId="Normal" w:default="1">
    <w:name w:val="Normal"/>
    <w:rPr><w:color w:val="1A1A1A"/><w:sz w:val="22"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Title">
    <w:name w:val="Title"/>
    <w:rPr><w:b/><w:color w:val="1A1A1A"/><w:sz w:val="36"/></w:rPr>
  </w:style>
</w:styles>"""

    // ── Transcript paragraph splitter ─────────────────────────────────────────

    private fun splitTranscriptToParagraphs(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        // Mode Multi Speaker: ada [Speaker X]: → pisah per speaker
        if (text.contains(Regex("\\[Speaker \\d+\\]:|\\[speaker \\d+\\]:"))) {
            return text.split(Regex("(?=\\[Speaker \\d+\\]:)|(?=\\[speaker \\d+\\]:)"))
                .map { it.trim() }.filter { it.isNotBlank() }
        }

        // Mode Record biasa: pecah per ~3-4 kalimat (tanda titik/tanya/seru)
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val paragraphs = mutableListOf<String>()
        val sb = StringBuilder()
        var count = 0
        for (sentence in sentences) {
            sb.append(sentence).append(" ")
            count++
            if (count >= 4) {
                paragraphs.add(sb.toString().trim())
                sb.clear()
                count = 0
            }
        }
        if (sb.isNotBlank()) paragraphs.add(sb.toString().trim())
        return paragraphs
    }

    // ── Translate PDF/DOCX Generator ──────────────────────────────────────────

    private fun generateTranslatePdf(note: Note, text: String, targetLang: String, outFile: File) {
        val langLabel = if (targetLang == "en") "English" else "Bahasa Indonesia"
        generateSingleSectionPdf(
            note = note,
            sectionTitle = "✦ TERJEMAHAN — $langLabel",
            content = text,
            outFile = outFile,
            isPoinKunci = false
        )
    }

    private fun generateTranslateDocx(note: Note, text: String, targetLang: String, outFile: File) {
        val langLabel = if (targetLang == "en") "English" else "Bahasa Indonesia"
        generateSingleSectionDocx(note, "TERJEMAHAN — $langLabel", text, false, outFile)
    }

    // ── Poin Kunci PDF/DOCX Generator ────────────────────────────────────────

    private fun generatePoinKunciPdf(note: Note, text: String, outFile: File) {
        generateSingleSectionPdf(note, "✦ POIN KUNCI", text, outFile, isPoinKunci = true)
    }

    private fun generatePoinKunciDocx(note: Note, text: String, outFile: File) {
        generateSingleSectionDocx(note, "POIN KUNCI", text, true, outFile)
    }

    // ── Single Section PDF (shared for Translate + Poin Kunci) ───────────────

    private fun generateSingleSectionPdf(
        note: Note, sectionTitle: String, content: String,
        outFile: File, isPoinKunci: Boolean
    ) {
        val doc = PdfDocument()
        val pageWidth = 595; val pageHeight = 842; val margin = 56f
        val colorPrimary = android.graphics.Color.parseColor("#1A7A40")
        val colorText    = android.graphics.Color.parseColor("#1A1A1A")
        val colorMuted   = android.graphics.Color.parseColor("#666666")
        val paintBg    = Paint().apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
        val paintAccent= Paint().apply { color = colorPrimary; style = Paint.Style.FILL }
        val paintTitle = Paint().apply { color = colorText; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val paintMeta  = Paint().apply { color = colorMuted; textSize = 11f; isAntiAlias = true }
        val paintHead  = Paint().apply { color = colorPrimary; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val paintBody  = Paint().apply { color = colorText; textSize = 12f; isAntiAlias = true }
        val paintSmall = Paint().apply { color = colorMuted; textSize = 10f; isAntiAlias = true }
        val paintCardBg= Paint().apply { color = android.graphics.Color.parseColor("#F5F9F6"); style = Paint.Style.FILL }

        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
        var page = doc.startPage(pageInfo); var canvas: Canvas = page.canvas; var y = margin

        fun drawBg() = canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), paintBg)
        fun newPage() {
            doc.finishPage(page); pageNum++
            pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            page = doc.startPage(pageInfo); canvas = page.canvas; y = margin; drawBg()
            canvas.drawRect(0f, 0f, 6f, pageHeight.toFloat(), paintAccent)
        }
        fun checkBreak(needed: Float) { if (y + needed > pageHeight - margin) newPage() }
        fun drawWrapped(text: String, paint: Paint, x: Float, maxW: Float) {
            if (text.isBlank()) return
            val words = text.split(" "); val sb = StringBuilder()
            for (word in words) {
                val test = if (sb.isEmpty()) word else "$sb $word"
                if (paint.measureText(test) > maxW) {
                    checkBreak(paint.textSize + 4f)
                    canvas.drawText(sb.toString(), x, y, paint); y += paint.textSize + 4f
                    sb.clear(); sb.append(word)
                } else { sb.clear(); sb.append(test) }
            }
            if (sb.isNotEmpty()) {
                checkBreak(paint.textSize + 4f)
                canvas.drawText(sb.toString(), x, y, paint); y += paint.textSize + 4f
            }
        }

        drawBg(); canvas.drawRect(0f, 0f, 6f, pageHeight.toFloat(), paintAccent)
        canvas.drawText("KajianNote", margin, y, paintSmall); y += 20f
        drawWrapped(note.title.ifBlank { "Catatan Kajian" }, paintTitle, margin, pageWidth - margin * 2); y += 6f
        canvas.drawText("${note.getFormattedDate()}  ·  ${note.getLanguageLabel()}  ·  ${note.wordCount} kata", margin, y, paintMeta); y += 20f
        canvas.drawRect(margin, y, pageWidth - margin, y + 1f, paintAccent); y += 16f

        checkBreak(24f); canvas.drawText(sectionTitle, margin, y, paintHead); y += 16f

        if (isPoinKunci) {
            // Poin Kunci: render per kartu (pisah dengan ---)
            val points = content.split("---").map { it.trim() }.filter { it.isNotBlank() }
            for (point in points) {
                val cardHeight = (point.lines().size + 2) * (paintBody.textSize + 4f) + 20f
                checkBreak(cardHeight + 16f)
                // Card background
                canvas.drawRoundRect(
                    android.graphics.RectF(margin - 4f, y - 8f, pageWidth - margin + 4f, y + cardHeight),
                    8f, 8f, paintCardBg
                )
                for (line in point.lines()) {
                    if (line.isNotBlank()) drawWrapped(line, paintBody, margin + 4f, pageWidth - margin * 2 - 8f)
                }
                y += 16f
            }
        } else {
            // Translate: render per paragraf biasa
            for (line in content.lines()) {
                if (line.isBlank()) { y += 8f; continue }
                drawWrapped(line, paintBody, margin, pageWidth - margin * 2); y += 4f
            }
        }

        val footerY = pageHeight - margin + 10f
        canvas.drawText("Diekspor dari KajianNote  ·  ${note.getFormattedDate()}", margin, footerY, paintSmall)
        doc.finishPage(page)
        FileOutputStream(outFile).use { doc.writeTo(it) }; doc.close()
    }

    // ── Single Section DOCX ───────────────────────────────────────────────────

    private fun generateSingleSectionDocx(
        note: Note, sectionTitle: String, content: String,
        isPoinKunci: Boolean, outFile: File
    ) {
        FileOutputStream(outFile).use { fos ->
            ZipOutputStream(fos).use { zip ->
                zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write(contentTypes().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("_rels/.rels")); zip.write(rootRels().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels")); zip.write(documentRels().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("word/styles.xml")); zip.write(styles().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(buildSingleSectionDocument(note, sectionTitle, content, isPoinKunci).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun buildSingleSectionDocument(
        note: Note, sectionTitle: String, content: String, isPoinKunci: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
""")
        sb.append(para(note.title.ifBlank { "Catatan Kajian" }, bold = true, size = 36))
        sb.append(para("${note.getFormattedDate()}  ·  ${note.wordCount} kata", size = 20, color = "888888"))
        sb.append(para(""))
        sb.append(para(sectionTitle, bold = true, size = 24, color = "1A7A40"))

        if (isPoinKunci) {
            val points = content.split("---").map { it.trim() }.filter { it.isNotBlank() }
            for (point in points) {
                sb.append(para(""))
                for (line in point.lines()) {
                    if (line.isNotBlank()) sb.append(para(line, size = 22))
                }
            }
        } else {
            for (line in content.lines()) {
                sb.append(para(if (line.isBlank()) "" else line, size = 22))
            }
        }

        sb.append(para(""))
        sb.append(para("Diekspor dari KajianNote  ·  ${note.getFormattedDate()}", size = 18, color = "888888"))
        sb.append("""
  </w:body>
</w:document>""")
        return sb.toString()
    }

    // ── File helper ───────────────────────────────────────────────────────────

    private fun getOutputFile(context: Context, note: Note, ext: String, suffix: String = ""): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "KajianNote")
        dir.mkdirs()
        val safeName = (note.title.ifBlank { "Kajian_${note.id}" })
            .replace(Regex("[^a-zA-Z0-9_\\-\\s]"), "")
            .trim().replace(" ", "_").take(40)
        return File(dir, "${safeName}.$ext")
    }
}
