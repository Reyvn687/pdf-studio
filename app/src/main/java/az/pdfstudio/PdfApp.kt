package az.pdfstudio

import android.app.Application
import az.pdfstudio.core.PdfOps
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        PdfOps.appContext = this
    }
}
