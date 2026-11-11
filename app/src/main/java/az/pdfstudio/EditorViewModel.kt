package az.pdfstudio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import az.pdfstudio.core.*
import az.pdfstudio.model.Annot
import az.pdfstudio.model.ParaModel
import az.pdfstudio.model.Pt
import az.pdfstudio.model.TextBlock
import az.pdfstudio.model.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Səhifə üzərində aktiv mətn redaktə sessiyası */
class EditSession(val page: Int, val model: ParaModel, val base: File)

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val ocr: OcrEngine = MlKitOcrEngine()          // ← OCR mühərrikini burada dəyişin
    private var renderer: PageRenderer? = null

    var work by mutableStateOf<File?>(null); private set
    var pageCount by mutableIntStateOf(0); private set
    var revision by mutableIntStateOf(0); private set
    var fileName by mutableStateOf("")
    val annots = mutableStateListOf<Annot>()
    var tool by mutableStateOf(Tool.VIEW)
    var color by mutableIntStateOf(0xFF1E40AF.toInt())
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var ocrText by mutableStateOf<String?>(null)
    var pendingText by mutableStateOf<Pair<Int, Pt>?>(null)

    // ---------- geri alma ----------
    private val history = ArrayDeque<File>()
    var historySize by mutableIntStateOf(0); private set

    private fun snapshot() {
        val f = File(ctx.cacheDir, "hist_${System.nanoTime()}.pdf")
        work!!.copyTo(f, true); history.addLast(f)
        while (history.size > 6) history.removeFirst().delete()
        historySize = history.size
    }

    // ---------- mətn bloklari + inline redaktə ----------
    val blocks = mutableStateMapOf<Int, List<TextBlock>>()
    private val loadingBlocks = HashSet<Int>()

    var edit by mutableStateOf<EditSession?>(null); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var editRev by mutableIntStateOf(0); private set
    var selA by mutableIntStateOf(0); private set
    var selB by mutableIntStateOf(0); private set
    private var renderJob: Job? = null

    fun ensureBlocks(page: Int) {
        val w = work ?: return
        if (blocks.containsKey(page) || !loadingBlocks.add(page)) return
        val rev = revision
        viewModelScope.launch {
            val res = try { withContext(Dispatchers.IO) { PdfOps.extractBlocks(w, page) } } catch (e: Exception) { emptyList() }
            if (rev == revision) blocks[page] = res
            loadingBlocks.remove(page)
        }
    }

    fun startEdit(b: TextBlock, px: Float, py: Float) {
        val seed = b.seed ?: return
        launchBusy("Hazırlanır…") {
            commit()
            val base = File(ctx.cacheDir, "edit_base.pdf")
            withContext(Dispatchers.IO) { PdfOps.makeSinglePage(work!!, b.page, base) }
            val m = seed.deepCopy()
            val i = m.hit(px, py)
            selA = i; selB = i; preview = null; editRev++
            edit = EditSession(b.page, m, base)
        }
    }

    private fun touched() { editRev++; scheduleRender() }

    private fun scheduleRender() {
        val s = edit ?: return
        val snap = s.model.deepCopy()
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(90)
            val bmp = try { withContext(Dispatchers.IO) { PdfOps.renderPreview(s.base, snap, 1400) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (edit === s && bmp != null) preview = bmp
        }
    }

    /** IME-dən gələn mətn dəyişikliyini modelə tətbiq edir. */
    fun onInput(text: String, a: Int, b: Int) {
        val s = edit ?: return; val m = s.model
        val old = m.flat()
        if (text != old) {
            val minL = min(old.length, text.length)
            var p = 0; while (p < minL && old[p] == text[p]) p++
            var q = 0; while (q < minL - p && old[old.length - 1 - q] == text[text.length - 1 - q]) q++
            m.replace(p, old.length - q, text.substring(p, text.length - q))
        }
        selA = a.coerceIn(0, m.chars.size); selB = b.coerceIn(0, m.chars.size)
        touched()
    }

    fun setCaret(i: Int) { selA = i; selB = i; editRev++ }
    fun setSelection(a: Int, b: Int) { selA = a; selB = b; editRev++ }
    fun selectWord(i: Int) { val m = edit?.model ?: return; val w = m.wordRange(i); selA = w.first; selB = w.second; editRev++ }

    private fun styleRange(): Pair<Int, Int>? {
        val m = edit?.model ?: return null
        var a = min(selA, selB); var b = max(selA, selB)
        if (a == b) { val w = m.wordRange(a); a = w.first; b = w.second }
        return if (b > a) a to b else null
    }

    fun toggleBold() { val m = edit?.model ?: return; val r = styleRange() ?: return; m.setStyle(r.first, r.second, !m.chars[r.first].bold, null, null, null); touched() }
    fun toggleItalic() { val m = edit?.model ?: return; val r = styleRange() ?: return; m.setStyle(r.first, r.second, null, !m.chars[r.first].italic, null, null); touched() }
    fun sizeStep(d: Float) { val m = edit?.model ?: return; val r = styleRange() ?: return; m.setStyle(r.first, r.second, null, null, d, null); touched() }
    fun setTextColor(c: Int) { val m = edit?.model ?: return; val r = styleRange() ?: return; m.setStyle(r.first, r.second, null, null, null, c); touched() }

    fun finishEdit() {
        val s = edit ?: return
        renderJob?.cancel(); edit = null; preview = null
        if (s.model.changed()) launchBusy("Yadda saxlanılır…") {
            snapshot()
            withContext(Dispatchers.IO) { PdfOps.commitModel(work!!, s.model) }
            reload()
        }
        s.base.delete()
    }

    fun cancelEdit() { renderJob?.cancel(); edit?.base?.delete(); edit = null; preview = null }

    // ---------- ümumi ----------
    private fun launchBusy(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        busy = label
        try { block() } catch (e: Exception) { message = "Xəta: ${e.message}" } finally { busy = null }
    }

    private suspend fun reload() {
        withContext(Dispatchers.IO) {
            renderer?.close()
            blocks.clear(); loadingBlocks.clear()
            renderer = PageRenderer(work!!).also { pageCount = it.pageCount }
        }
        revision++
    }

    private suspend fun commit() {
        if (annots.isEmpty()) return
        withContext(Dispatchers.IO) { snapshot(); PdfOps.applyAnnots(work!!, annots.toList()) }
        annots.clear(); reload()
    }

    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) { renderer?.render(index, width) }

    private fun nameOf(uri: Uri) = ctx.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    } ?: "sənəd.pdf"

    /** Bir və ya bir neçə sənədi (PDF / şəkil) açır; çoxlu olarsa birləşdirir. */
    fun open(vararg uris: Uri) = launchBusy("Açılır…") {
        withContext(Dispatchers.IO) {
            val out = File(ctx.cacheDir, "work.pdf")
            val first = uris.first()
            DocumentFormats.resolve(ctx, first).toPdf(ctx, first, out)
            fileName = nameOf(first).substringBeforeLast('.') + ".pdf"
            if (uris.size > 1) PdfOps.merge(out, toPdfFiles(uris.drop(1)))
            work = out
        }
        annots.clear(); tool = Tool.VIEW; reload()
    }

    private fun toPdfFiles(uris: List<Uri>) = uris.mapIndexed { i, u ->
        File(ctx.cacheDir, "part$i.pdf").also { DocumentFormats.resolve(ctx, u).toPdf(ctx, u, it) }
    }

    fun merge(uris: List<Uri>) = launchBusy("Birləşdirilir…") {
        commit(); snapshot()
        withContext(Dispatchers.IO) { PdfOps.merge(work!!, toPdfFiles(uris)) }
        reload()
    }

    fun rotate(page: Int) = launchBusy("Fırladılır…") { commit(); snapshot(); withContext(Dispatchers.IO) { PdfOps.rotatePage(work!!, page) }; reload() }
    fun delete(page: Int) = launchBusy("Silinir…") { commit(); snapshot(); withContext(Dispatchers.IO) { PdfOps.deletePage(work!!, page) }; reload() }

    fun save(dest: Uri) = launchBusy("Yadda saxlanılır…") {
        commit()
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(dest, "wt")!!.use { o -> work!!.inputStream().use { it.copyTo(o) } }
        }
        message = "Yadda saxlanıldı ✓"
    }

    fun ocrPage(page: Int) = launchBusy("OCR işləyir…") {
        commit()
        val bmp = render(page, 1800) ?: error("Səhifə render olunmadı")
        ocrText = ocr.recognize(bmp).joinToString("\n") { it.text }.ifBlank { "Mətn tapılmadı" }
    }

    fun ocrImage(uri: Uri) = launchBusy("OCR işləyir…") {
        val bmp = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } }
        ocrText = ocr.recognize(bmp).joinToString("\n") { it.text }.ifBlank { "Mətn tapılmadı" }
    }

    fun makeSearchable() = launchBusy("OCR…") {
        commit(); snapshot()
        PdfOps.makeSearchable(work!!, ocr) { i, n -> busy = "OCR: $i / $n" }
        reload(); message = "Sənəd indi axtarıla bilən PDF-dir ✓"
    }

    fun add(a: Annot) { annots.add(a) }

    fun undo() {
        if (annots.isNotEmpty()) { annots.removeAt(annots.lastIndex); return }
        val f = history.removeLastOrNull() ?: return
        historySize = history.size
        launchBusy("Geri alınır…") {
            withContext(Dispatchers.IO) { work!!.delete(); if (!f.renameTo(work!!)) f.copyTo(work!!, true) }
            reload()
        }
    }

    fun closeDoc() {
        cancelEdit(); renderer?.close(); renderer = null; work = null; annots.clear(); pageCount = 0
        blocks.clear(); history.forEach { it.delete() }; history.clear(); historySize = 0
    }
}
