package io.itsikh.finnencer.share

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/**
 * One-stop helpers for sharing summaries (plain text + on-device PDF) and
 * podcasts (transcoded AAC/.m4a) through the Android share sheet.
 *
 * Outputs land under `cacheDir/share` and are exposed through the existing
 * FileProvider authority `${applicationId}.fileprovider` (configured in
 * `res/xml/file_paths.xml`).
 */
object ShareHelpers {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** Fire the system share sheet with [text] as the EXTRA_TEXT body. */
    fun shareText(context: Context, text: String, subject: String? = null) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        context.startActivity(Intent.createChooser(send, subject ?: "Share text").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Render [body] as a single-column PDF and fire the share sheet.
     * Layout: 8.5"x11" Letter (612x792pt), ~54pt margins, sans body at
     * 11pt with 1.4 leading. Title (16pt bold) + optional attribution
     * line (10pt grey) precede the body.
     */
    fun shareTextAsPdf(
        context: Context,
        title: String,
        attribution: String?,
        body: String,
        filename: String,
    ): File {
        val out = File(context.cacheDir, "share").apply { mkdirs() }
        val pdfFile = File(out, sanitize(filename) + ".pdf")
        val pageWidth = 612
        val pageHeight = 792
        val margin = 54
        val contentWidth = pageWidth - margin * 2

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF101317.toInt()
            textSize = 16f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val attrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6E7080.toInt()
            textSize = 10f
            typeface = Typeface.SANS_SERIF
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1E2C.toInt()
            textSize = 11f
            typeface = Typeface.SANS_SERIF
        }

        val titleLayout = staticLayout(title, titlePaint, contentWidth)
        val attrLayout = attribution?.let { staticLayout(it, attrPaint, contentWidth) }
        val bodyLayout = staticLayout(body, bodyPaint, contentWidth, lineSpacingMultiplier = 1.4f)

        val pdf = PdfDocument()
        try {
            var bodyStartLine = 0
            var pageNumber = 1
            while (true) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                var y = margin

                if (pageNumber == 1) {
                    canvas.save()
                    canvas.translate(margin.toFloat(), y.toFloat())
                    titleLayout.draw(canvas)
                    canvas.restore()
                    y += titleLayout.height + 6
                    if (attrLayout != null) {
                        canvas.save()
                        canvas.translate(margin.toFloat(), y.toFloat())
                        attrLayout.draw(canvas)
                        canvas.restore()
                        y += attrLayout.height + 14
                    } else {
                        y += 10
                    }
                }

                val available = pageHeight - margin - y
                val lineHeight = bodyLayout.getLineBottom(0) - bodyLayout.getLineTop(0)
                val linesFit = (available / lineHeight).coerceAtLeast(1)
                val endLine = (bodyStartLine + linesFit).coerceAtMost(bodyLayout.lineCount)
                val topOffset = bodyLayout.getLineTop(bodyStartLine)
                val bottomOffset = bodyLayout.getLineTop(endLine)

                canvas.save()
                canvas.translate(margin.toFloat(), (y - topOffset).toFloat())
                canvas.clipRect(0, topOffset, contentWidth, bottomOffset)
                bodyLayout.draw(canvas)
                canvas.restore()

                pdf.finishPage(page)
                bodyStartLine = endLine
                if (bodyStartLine >= bodyLayout.lineCount) break
                pageNumber++
                if (pageNumber > 200) break
            }
            pdfFile.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        sendFile(context, pdfFile, mime = "application/pdf", subject = title)
        return pdfFile
    }

    /** Share an arbitrary file (already on disk) through the OS share sheet. */
    fun shareFile(context: Context, file: File, mime: String, subject: String? = null) {
        sendFile(context, file, mime, subject)
    }

    private fun sendFile(context: Context, file: File, mime: String, subject: String?) {
        val authority = context.packageName + AUTHORITY_SUFFIX
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, subject ?: "Share").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun staticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        lineSpacingMultiplier: Float = 1.2f,
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, lineSpacingMultiplier)
        .setIncludePad(false)
        .build()

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80).ifBlank { "share" }
}
