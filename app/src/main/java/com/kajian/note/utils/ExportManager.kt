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

    fun getFileUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // ── PDF Generator ─────────────────────────────────────────────────────────

    private fun generatePdf(note: Note, summary: String?, outFile: File) {
        val doc = PdfDocument()
        val pageWidth  = 595
        val pageHeight = 842
        val margin     = 56f

        val colorPrimary = Color.parseColor("#1DB954")
        val colorDark    = Color.parseColor("#121212")
        val colorSurface = Color.parseColor("#1E1E1E")
        val colorText    = Color.parseColor("#EEEEEE")
        val colorMuted   = Color.parseColor("#888888")

        val paintBg     = Paint().apply { color = colorDark;    style = Paint.Style.FILL }
        val paintAccent = Paint().apply { color = colorPrimary; style = Paint.Style.FILL }
        val paintTitle  = Paint().apply { color = Color.WHITE;  textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
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
        for (line in note.plainText.lines()) {
            if (line.isBlank()) { y += 8f; continue }
            checkBreak(paintBody.textSize + 6f)
            drawWrapped(line, paintBody, margin, pageWidth - margin * 2)
            y += 2f
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
            sb.append(para("RINGKASAN", bold = true, size = 24, color = "1DB954"))
            for (line in summary.lines()) {
                if (line.isNotBlank()) sb.append(para(line, size = 22))
            }
            sb.append(para("")) // spacer
        }

        // Transcript
        sb.append(para("TRANSKRIPSI", bold = true, size = 24, color = "1DB954"))
        for (line in note.plainText.lines()) {
            sb.append(para(if (line.isBlank()) "" else line, size = 22))
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
        color: String = "EEEEEE"
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
    <w:rPr><w:color w:val="EEEEEE"/><w:sz w:val="22"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Title">
    <w:name w:val="Title"/>
    <w:rPr><w:b/><w:color w:val="FFFFFF"/><w:sz w:val="36"/></w:rPr>
  </w:style>
</w:styles>"""

    // ── File helper ───────────────────────────────────────────────────────────

    private fun getOutputFile(context: Context, note: Note, ext: String): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "KajianNote")
        dir.mkdirs()
        val safeName = (note.title.ifBlank { "Kajian_${note.id}" })
            .replace(Regex("[^a-zA-Z0-9_\\-\\s]"), "")
            .trim().replace(" ", "_").take(40)
        return File(dir, "${safeName}.$ext")
    }
}
