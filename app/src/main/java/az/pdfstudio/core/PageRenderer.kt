package az.pdfstudio.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

class PageRenderer(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val r = PdfRenderer(pfd)
    private var closed = false
    val pageCount: Int = r.pageCount

    @Synchronized
    fun render(index: Int, width: Int): Bitmap? {
        if (closed || index !in 0 until pageCount) return null
        r.openPage(index).use { p ->
            val h = (width.toFloat() * p.height / p.width).toInt()
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    @Synchronized
    fun close() { if (!closed) { closed = true; r.close(); pfd.close() } }
}
