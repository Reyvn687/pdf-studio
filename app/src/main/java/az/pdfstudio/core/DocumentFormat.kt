package az.pdfstudio.core

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File

/**
 * GƏLƏCƏK GENİŞLƏNMƏ NÖQTƏSİ:
 * Yeni format (DOCX, EPUB, TXT...) əlavə etmək üçün bu interfeysi implement edib
 * [DocumentFormats.all] siyahısına qoşmaq kifayətdir. Qalan hər şey (redaktə, OCR, saxlama)
 * avtomatik işləyəcək, çünki daxildə hər şey PDF-ə çevrilir.
 */
interface DocumentFormat {
    val mimeTypes: List<String>
    fun toPdf(ctx: Context, uri: Uri, out: File)
}

object PdfFormat : DocumentFormat {
    override val mimeTypes = listOf("application/pdf")
    override fun toPdf(ctx: Context, uri: Uri, out: File) {
        ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
    }
}

object ImageFormat : DocumentFormat {
    override val mimeTypes = listOf("image/jpeg", "image/png", "image/webp")
    override fun toPdf(ctx: Context, uri: Uri, out: File) {
        val bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            ?: error("Şəkil oxunmadı")
        val pw = 595f
        val ph = pw * bmp.height / bmp.width
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(pw, ph)); doc.addPage(page)
            val img = JPEGFactory.createFromImage(doc, bmp, 0.92f)
            PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, pw, ph) }
            doc.save(out)
        }
        bmp.recycle()
    }
}

object DocumentFormats {
    val all: List<DocumentFormat> = listOf(PdfFormat, ImageFormat)
    val pickerMimes: Array<String> get() = all.flatMap { it.mimeTypes }.toTypedArray()
    fun resolve(ctx: Context, uri: Uri): DocumentFormat {
        val mime = ctx.contentResolver.getType(uri)
        return all.firstOrNull { mime in it.mimeTypes } ?: PdfFormat
    }
}
