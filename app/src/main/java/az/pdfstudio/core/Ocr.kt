package az.pdfstudio.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class OcrLine(val text: String, val l: Float, val t: Float, val r: Float, val b: Float)

/**
 * OCR mühərriki dəyişdirilə bilər. Azərbaycan dili (ə, ı, ş, ç, ğ) üçün daha dəqiq nəticə istəsəniz,
 * Tesseract4Android + az.traineddata ilə bu interfeysi implement edin və ViewModel-də əvəz edin.
 */
interface OcrEngine { suspend fun recognize(bitmap: Bitmap): List<OcrLine> }

class MlKitOcrEngine : OcrEngine {
    private val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    override suspend fun recognize(bitmap: Bitmap): List<OcrLine> {
        val res = rec.process(InputImage.fromBitmap(bitmap, 0)).await()
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        return res.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { OcrLine(line.text, it.left / w, it.top / h, it.right / w, it.bottom / h) }
        }
    }
}
