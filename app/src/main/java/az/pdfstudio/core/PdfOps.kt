package az.pdfstudio.core

import android.content.Context
import az.pdfstudio.model.Annot
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import az.pdfstudio.model.*
import com.tom_roush.pdfbox.cos.COSDictionary
import java.io.ByteArrayOutputStream
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.util.Collections
import java.util.IdentityHashMap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object PdfOps {
    lateinit var appContext: Context

    private inline fun edit(file: File, block: (PDDocument) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        PDDocument.load(file).use { d -> block(d); d.save(tmp) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Unicode (ə, ş, ğ...) dəstəkləyən şrift. Öz şriftinizi assets/fonts/font.ttf kimi qoya bilərsiniz. */
    private fun loadFont(doc: PDDocument): PDFont {
        try { appContext.assets.open("fonts/font.ttf").use { return PDType0Font.load(doc, it) } } catch (_: Exception) {}
        for (p in listOf("/system/fonts/NotoSans-Regular.ttf", "/system/fonts/Roboto-Regular.ttf", "/system/fonts/DroidSans.ttf")) {
            val f = File(p)
            if (f.exists()) try { return PDType0Font.load(doc, f) } catch (_: Exception) {}
        }
        return PDType1Font.HELVETICA
    }

    private fun PDFont.safe(s: String) = buildString {
        for (c in s) {
            if (c < ' ') continue
            try { getStringWidth(c.toString()); append(c) } catch (_: Exception) {}
        }
    }

    /** Səhifənin (fırlanma daxil) "göstərilən" koordinatlarını PDF fəzasına çevirir. */
    private class Geo(page: PDPage) {
        val box = page.cropBox
        val w = box.width; val h = box.height
        val rot = ((page.rotation % 360) + 360) % 360
        val dispW = if (rot == 90 || rot == 270) h else w
        val dispH = if (rot == 90 || rot == 270) w else h
        fun x(nx: Float, ny: Float) = box.lowerLeftX + when (rot) {
            90 -> ny * w; 180 -> w - nx * w; 270 -> w - ny * w; else -> nx * w }
        fun y(nx: Float, ny: Float) = box.lowerLeftY + when (rot) {
            90 -> nx * h; 180 -> ny * h; 270 -> h - nx * h; else -> (1 - ny) * h }
    }

    private fun PDPageContentStream.textAt(font: PDFont, txt: String, size: Float, g: Geo, nx: Float, nyBase: Float, hScale: Float = 1f) {
        var m = Matrix.getRotateInstance(Math.toRadians(-g.rot.toDouble()), g.x(nx, nyBase), g.y(nx, nyBase))
        if (hScale != 1f) m = Matrix(hScale, 0f, 0f, 1f, 0f, 0f).multiply(m)
        beginText(); setFont(font, size); setTextMatrix(m); showText(txt); endText()
    }

    private fun rgb(c: Int) = Triple(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)

    fun applyAnnots(file: File, annots: List<Annot>) {
        if (annots.isEmpty()) return
        edit(file) { doc ->
            val font = loadFont(doc)
            annots.groupBy { it.page }.forEach { (p, list) ->
                val page = doc.getPage(p); val g = Geo(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    list.forEach { a ->
                        when (a) {
                            is Annot.Highlight -> rect(cs, g, a.l, a.t, a.r, a.b, a.color, 0.35f)
                            is Annot.Cover -> rect(cs, g, a.l, a.t, a.r, a.b, 0xFFFFFFFF.toInt(), 1f)
                            is Annot.Ink -> {
                                val (r, gr, b) = rgb(a.color)
                                cs.setStrokingColor(r, gr, b); cs.setLineWidth(1.8f)
                                a.pts.forEachIndexed { i, pt ->
                                    if (i == 0) cs.moveTo(g.x(pt.x, pt.y), g.y(pt.x, pt.y)) else cs.lineTo(g.x(pt.x, pt.y), g.y(pt.x, pt.y))
                                }
                                cs.stroke()
                            }
                            is Annot.Text -> {
                                val (r, gr, b) = rgb(a.color)
                                cs.setNonStrokingColor(r, gr, b)
                                val size = a.size * g.dispW
                                a.text.split("\n").forEachIndexed { i, line ->
                                    val ny = a.y + (size * (0.95f + 1.2f * i)) / g.dispH
                                    val s = font.safe(line)
                                    if (s.isNotBlank()) cs.textAt(font, s, size, g, a.x, ny)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun rect(cs: PDPageContentStream, g: Geo, l: Float, t: Float, r: Float, b: Float, color: Int, alpha: Float) {
        val x1 = g.x(l, t); val y1 = g.y(l, t); val x2 = g.x(r, b); val y2 = g.y(r, b)
        val (cr, cg, cb) = rgb(color)
        cs.saveGraphicsState()
        val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = alpha }
        cs.setGraphicsStateParameters(gs)
        cs.setNonStrokingColor(cr, cg, cb)
        cs.addRect(min(x1, x2), min(y1, y2), abs(x2 - x1), abs(y2 - y1))
        cs.fill()
        cs.restoreGraphicsState()
    }

    fun rotatePage(file: File, index: Int) = edit(file) { it.getPage(index).rotation = (it.getPage(index).rotation + 90) % 360 }

    fun deletePage(file: File, index: Int) = edit(file) { if (it.numberOfPages > 1) it.removePage(index) }

    /** Əsas faylın sonuna başqa PDF-lər əlavə edir. */
    fun merge(file: File, others: List<File>) {
        val tmp = File(file.parentFile, "merged.tmp")
        PDFMergerUtility().apply {
            addSource(file); others.forEach { addSource(it) }
            destinationFileName = tmp.absolutePath
            mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
        }
        file.delete(); tmp.renameTo(file)
    }

    /** Skan PDF-ni axtarıla/seçilə bilən edir: hər səhifəyə görünməz OCR mətn qatı əlavə olunur. */
    suspend fun makeSearchable(file: File, ocr: OcrEngine, progress: (Int, Int) -> Unit) {
        val results = ArrayList<List<OcrLine>>()
        val rr = PageRenderer(file)
        try {
            for (i in 0 until rr.pageCount) {
                progress(i + 1, rr.pageCount)
                val bmp = rr.render(i, 1800)
                if (bmp == null) { results.add(emptyList()); continue }
                results.add(ocr.recognize(bmp)); bmp.recycle()
            }
        } finally { rr.close() }
        edit(file) { doc ->
            val font = loadFont(doc)
            results.forEachIndexed { i, lines ->
                val page = doc.getPage(i); val g = Geo(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setRenderingMode(RenderingMode.NEUTRAL) // görünməz
                    lines.forEach { l ->
                        val s = font.safe(l.text)
                        if (s.isNotBlank()) cs.textAt(font, s, max(4f, (l.b - l.t) * g.dispH * 0.85f), g, l.l, l.b - (l.b - l.t) * 0.15f)
                    }
                }
            }
        }
    }

    // =====================================================================
    //  INLINE MƏTN REDAKTƏSİ – orijinal şriftlə, yalnız dəyişən sətirlər
    // =====================================================================

    fun extractBlocks(file: File, pageIndex: Int): List<TextBlock> =
        PDDocument.load(file).use { doc ->
            val c = Collector()
            c.setStartPage(pageIndex + 1); c.setEndPage(pageIndex + 1)
            c.getText(doc)
            buildBlocks(pageIndex, c.words, c.glyphs, c.fontInfos)
        }

    /** Redaktə sessiyası üçün tək səhifəlik PDF (sürətli önizləmə). */
    fun makeSinglePage(file: File, pageIdx: Int, out: File) {
        PDDocument.load(file).use { src ->
            PDDocument().use { d -> d.importPage(src.getPage(pageIdx)); d.save(out) }
        }
    }

    fun commitModel(file: File, m: ParaModel) = edit(file) { applyModel(it, m.page, m) }

    fun renderPreview(base: File, m: ParaModel, width: Int): Bitmap? {
        val tmp = File(base.parentFile, "pv_${System.nanoTime()}.pdf")
        base.copyTo(tmp, true)
        try {
            edit(tmp) { applyModel(it, 0, m) }
            val r = PageRenderer(tmp)
            try { return r.render(0, width) } finally { r.close() }
        } finally { tmp.delete() }
    }

    private fun styledFont(doc: PDDocument, serif: Boolean, bold: Boolean, italic: Boolean): PDFont {
        val style = when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Regular" }
        val base = if (serif) "NotoSerif" else "NotoSans"
        try { appContext.assets.open("fonts/$base-$style.ttf").use { return PDType0Font.load(doc, it) } } catch (_: Exception) {}
        return loadFont(doc)
    }

    private fun tm(g: Geo, x: Float, base: Float): Matrix {
        val nx = x / g.dispW; val ny = base / g.dispH
        return Matrix.getRotateInstance(Math.toRadians(-g.rot.toDouble()), g.x(nx, ny), g.y(nx, ny))
    }

    fun applyModel(doc: PDDocument, pageIdx: Int, m: ParaModel) {
        val anyDirty = m.lines.any { it.dirty }
        val removeIdx = m.orig.indices.filter { i -> m.lines.none { it.origIdx == i && !it.dirty } }
        if (!anyDirty && removeIdx.isEmpty()) return
        if (removeIdx.isNotEmpty()) removeOps(doc, pageIdx, removeIdx.map { m.orig[it] })

        val page = doc.getPage(pageIdx); val g = Geo(page)
        val fb = HashMap<String, PDFont>()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            m.lines.forEachIndexed { j, ln ->
                if (!ln.dirty) return@forEachIndexed
                val xs = m.layoutLine(j); val s = ln.start; val n = xs.size
                var i = 0
                while (i < n) {
                    val c = m.chars[s + i]
                    if (c.trailing || c.cont || c.ch == ' ' || c.ch == '\u00A0') { i++; continue }
                    val startX = xs[i]; var endX = startX
                    val hex = StringBuilder(); val txt = StringBuilder()
                    var k = i
                    while (k < n) {
                        val d = m.chars[s + k]
                        if (d.trailing || d.ch == ' ') break
                        if (d.cont) { k++; continue }
                        if (d.font != c.font || d.size != c.size || d.color != c.color || d.fallback != c.fallback ||
                            (c.fallback && (d.bold != c.bold || d.italic != c.italic))) break
                        if (k > i && abs(xs[k] - endX) > 0.05f) break
                        if (d.fallback) txt.append(d.ch) else d.bytes?.forEach { hex.append("%02X".format(it)) }
                        endX = xs[k] + d.adv; k++
                    }
                    val (cr, cg, cb) = rgb(c.color)
                    cs.setNonStrokingColor(cr, cg, cb)
                    if (c.fallback) {
                        val key = "${c.serif}${c.bold}${c.italic}"
                        val f = fb.getOrPut(key) { styledFont(doc, c.serif, c.bold, c.italic) }
                        val t = f.safe(txt.toString())
                        if (t.isNotEmpty()) { cs.beginText(); cs.setFont(f, c.size); cs.setTextMatrix(tm(g, startX, ln.base)); cs.showText(t); cs.endText() }
                    } else if (c.font != null && hex.isNotEmpty()) {
                        cs.beginText(); cs.setTextMatrix(tm(g, startX, ln.base))
                        cs.appendRawCommands("/" + c.font + " " + String.format(java.util.Locale.US, "%.3f", c.size) + " Tf <" + hex + "> Tj\n")
                        cs.endText()
                    }
                    i = if (k > i) k else i + 1
                }
            }
        }
    }

    private fun removeOps(doc: PDDocument, pageIdx: Int, lines: List<OrigLine>) {
        val finder = OpFinder(lines)
        finder.setStartPage(pageIdx + 1); finder.setEndPage(pageIdx + 1)
        finder.getText(doc)
        if (finder.hits.isEmpty()) return
        val page = doc.getPage(pageIdx)
        val parser = PDFStreamParser(page); parser.parse()
        val tokens = parser.tokens
        var idx = -1
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t is Operator) {
                idx++
                if (idx in finder.hits && i > 0) tokens[i - 1] = if (t.name == "TJ") COSArray() else COSString("")
            }
        }
        val ns = PDStream(doc)
        ns.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(tokens) }
        page.setContents(ns)
    }
}

/** Orijinal şrift olmayan simvollar üçün ölçü (Noto şrifti ilə eyni). */
internal object FallbackM : FallbackMetrics {
    private val cache = HashMap<String, Paint>()
    override fun adv(ch: Char, size: Float, bold: Boolean, italic: Boolean, serif: Boolean): Float {
        val style = when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Regular" }
        val name = (if (serif) "NotoSerif" else "NotoSans") + "-" + style
        val p = cache.getOrPut(name) {
            Paint().apply {
                try { typeface = Typeface.createFromAsset(PdfOps.appContext.assets, "fonts/$name.ttf") } catch (_: Exception) {}
                textSize = 1000f
            }
        }
        return p.measureText(ch.toString()) / 1000f * size
    }
}

// ---------------- mətn çıxarma köməkçiləri ----------------

private class WordInfo(
    val text: String, val l: Float, val r: Float, val base: Float, val size: Float, val color: Int,
    val bold: Boolean, val italic: Boolean, val serif: Boolean, val pw: Float, val ph: Float,
    val glyphs: List<GChar>
)

private class LineInfo(val words: List<WordInfo>) {
    val l = words.first().l
    val r = words.last().r
    val base = words.first().base
    val size = words.map { it.size }.average().toFloat()
    val text = words.joinToString(" ") { it.text }
}

private fun familyOf(raw: String): String {
    var n = raw.substringAfter('+').lowercase()
    for (t in listOf("bolditalic", "boldoblique", "semibold", "bold", "italic", "oblique", "regular", "black", "heavy", "light", "medium", "mt", "ps"))
        n = n.replace(t, "")
    return n.filter { it.isLetter() }
}

private fun codeBytes(font: PDFont?, codes: IntArray): ByteArray {
    val two = font is PDType0Font
    val out = ByteArrayOutputStream()
    for (c in codes) {
        if (two || c > 255) { out.write((c shr 8) and 255); out.write(c and 255) } else out.write(c)
    }
    return out.toByteArray()
}

private class Collector : PDFTextStripper() {
    val words = ArrayList<WordInfo>()
    val glyphs = HashMap<String, GlyphDef>()
    val fontInfos = HashMap<String, FontInfo>()
    private val fontNames = IdentityHashMap<COSDictionary, String>()
    private val colors = IdentityHashMap<TextPosition, Int>()
    private val hidden = Collections.newSetFromMap(IdentityHashMap<TextPosition, Boolean>())
    private val styleCache = IdentityHashMap<COSDictionary, BooleanArray>()

    init { setSortByPosition(true) }

    override fun startPage(page: PDPage) {
        super.startPage(page)
        try {
            val fd = page.resources?.cosObject?.getDictionaryObject(COSName.FONT) as? COSDictionary
            fd?.keySet()?.forEach { k -> (fd.getDictionaryObject(k) as? COSDictionary)?.let { fontNames[it] = k.name } }
        } catch (_: Exception) {}
    }

    private fun styleOf(f: PDFont?): BooleanArray {
        val key = f?.cosObject ?: return booleanArrayOf(false, false, false)
        return styleCache.getOrPut(key) {
            val name = (f.name ?: "").lowercase()
            val d = f.fontDescriptor
            val bold = "bold" in name || "black" in name || "heavy" in name || (d?.fontWeight ?: 0f) >= 700f
            val italic = "italic" in name || "oblique" in name || d?.isItalic == true
            val serif = when {
                listOf("sans", "arial", "helvet", "calibri", "verdana", "tahoma", "segoe").any { it in name } -> false
                listOf("serif", "times", "garamond", "minion", "palatino", "georgia", "cambria", "book", "roman").any { it in name } -> true
                else -> d?.isSerif == true
            }
            booleanArrayOf(bold, italic, serif)
        }
    }

    override fun processTextPosition(text: TextPosition) {
        try { colors[text] = graphicsState.nonStrokingColor.toRGB() } catch (_: Exception) {}
        try { if (graphicsState.textState.renderingMode == RenderingMode.NEUTRAL) hidden.add(text) } catch (_: Exception) {}
        val name = fontNames[text.font?.cosObject]
        val u = text.unicode
        if (name != null && !u.isNullOrEmpty() && text.fontSizeInPt > 0f) {
            val codes = text.characterCodes
            if (codes != null && codes.isNotEmpty())
                glyphs.getOrPut("$name|$u") { GlyphDef(codeBytes(text.font, codes), text.widthDirAdj / text.fontSizeInPt) }
            if (!fontInfos.containsKey(name)) {
                val st = styleOf(text.font)
                fontInfos[name] = FontInfo(familyOf(text.font?.name ?: name), st[0], st[1], st[2])
            }
        }
        super.processTextPosition(text)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        val p = textPositions.firstOrNull() ?: return
        val last = textPositions.last()
        if (hidden.contains(p) || text.isBlank()) return
        val st = styleOf(p.font)
        val size = if (p.fontSizeInPt > 0f) p.fontSizeInPt else p.heightDir
        val gl = ArrayList<GChar>()
        for (tp in textPositions) {
            val nm = fontNames[tp.font?.cosObject]
            val s2 = styleOf(tp.font)
            val u = tp.unicode.let { if (it.isNullOrEmpty()) "\uFFFD" else it }
            val codes = tp.characterCodes
            val bytes = if (nm != null && codes != null && codes.isNotEmpty()) codeBytes(tp.font, codes) else null
            val sz = if (tp.fontSizeInPt > 0f) tp.fontSizeInPt else size
            val col = (colors[tp] ?: 0) or 0xFF000000.toInt()
            u.forEachIndexed { k, ch ->
                gl.add(GChar(ch, nm, if (k == 0) bytes else null, sz, col, if (k == 0) tp.widthDirAdj else 0f, tp.xDirAdj,
                    s2[0], s2[1], s2[2], cont = k > 0, fallback = (nm == null || bytes == null) && k == 0))
            }
        }
        words.add(WordInfo(text.trim(), p.xDirAdj, last.xDirAdj + last.widthDirAdj, p.yDirAdj, size,
            (colors[p] ?: 0) or 0xFF000000.toInt(), st[0], st[1], st[2], p.pageWidth, p.pageHeight, gl))
    }
}

/** Verilən sətirlərə aid show-text əməliyyatlarının (Tj/TJ/'/") sıra nömrələrini tapır. */
private class OpFinder(val lines: List<OrigLine>) : PDFTextStripper() {
    val hits = HashSet<Int>()
    private var depth = 0
    private var idx = -1
    private var cur = -1
    private var first: TextPosition? = null
    private val show = setOf("Tj", "TJ", "'", "\"")

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
        var isShow = false
        if (depth == 0) { idx++; isShow = operator.name in show; if (isShow) { cur = idx; first = null } }
        depth++
        try { super.processOperator(operator, operands) } finally { depth-- }
        if (isShow) {
            first?.let { p ->
                val x = p.xDirAdj; val y = p.yDirAdj
                if (lines.any { x >= it.x - 3f && x <= it.r + 3f && abs(y - it.base) < 0.45f * it.size }) hits.add(cur)
            }
            cur = -1
        }
    }

    override fun processTextPosition(text: TextPosition) {
        if (cur >= 0 && first == null) first = text
        super.processTextPosition(text)
    }
}

private fun spaceAfter(prev: GChar, glyphs: Map<String, GlyphDef>): GChar {
    val d = prev.font?.let { glyphs["$it| "] }
    return GChar(' ', prev.font, d?.bytes, prev.size, prev.color, d?.let { it.advEm * prev.size } ?: (prev.size * 0.25f),
        prev.x + prev.adv, prev.bold, prev.italic, prev.serif)
}

private fun buildBlocks(page: Int, words: List<WordInfo>, glyphs: Map<String, GlyphDef>, fonts: Map<String, FontInfo>): List<TextBlock> {
    if (words.isEmpty()) return emptyList()
    // 1) sətirlər
    val rows = ArrayList<MutableList<WordInfo>>()
    for (w in words.sortedBy { it.base }) {
        val row = rows.lastOrNull()
        if (row != null && abs(row[0].base - w.base) < 0.35f * w.size) row.add(w) else rows.add(mutableListOf(w))
    }
    val lines = ArrayList<LineInfo>()
    for (row in rows) {
        var seg = mutableListOf<WordInfo>()
        for (w in row.sortedBy { it.l }) {
            if (seg.isNotEmpty() && w.l - seg.last().r > 2.5f * w.size) { lines.add(LineInfo(seg)); seg = mutableListOf() }
            seg.add(w)
        }
        if (seg.isNotEmpty()) lines.add(LineInfo(seg))
    }
    // 2) abzaslar
    val blocks = ArrayList<MutableList<LineInfo>>()
    for (ln in lines) {
        var target: MutableList<LineInfo>? = null
        for (blk in blocks.takeLast(6).asReversed()) {
            val last = blk.last()
            val dy = ln.base - last.base
            val bl = blk.minOf { it.l }; val br = blk.maxOf { it.r }
            val ok = dy > 0 && dy <= 1.9f * last.size &&
                abs(ln.size - last.size) <= 0.15f * last.size &&
                ln.l < br && ln.r > bl &&
                !(ln.l - last.l > 0.8f * last.size) &&
                !(blk.size >= 2 && last.r < br - 2.5f * last.size)
            if (ok) { target = blk; break }
        }
        if (target != null) target.add(ln) else blocks.add(mutableListOf(ln))
    }
    // 3) TextBlock + ParaModel
    return blocks.mapNotNull { blk ->
        val first = blk.first()
        val l = blk.minOf { it.l }; val r = blk.maxOf { it.r }
        val size = blk.map { it.size }.average().toFloat()
        val n = blk.size
        val pitch = if (n > 1) (blk.last().base - first.base) / (n - 1) else size * 1.25f
        val justify = n >= 3 && blk.dropLast(1).all { r - it.r < 0.03f * (r - l) + 1f }

        val chars = ArrayList<GChar>(); val mlines = ArrayList<MLine>(); val orig = ArrayList<OrigLine>()
        blk.forEachIndexed { i, ln ->
            val start = chars.size
            ln.words.forEachIndexed { wi, w ->
                if (wi > 0 && chars.size > start) chars.add(spaceAfter(chars.last(), glyphs))
                chars.addAll(w.glyphs)
            }
            if (chars.size == start) return@forEachIndexed
            if (i < n - 1) {
                val lc = chars.last()
                val nf = blk[i + 1].words.first().glyphs.firstOrNull()
                val hyph = lc.ch == '-' && nf != null && nf.ch.isLowerCase()
                if (!hyph) chars.add(spaceAfter(lc, glyphs).also { it.trailing = true })
            }
            mlines.add(MLine(start, chars[start].x, ln.base, orig.size, false))
            orig.add(OrigLine(ln.l, ln.r, ln.base, ln.size))
        }
        if (chars.isEmpty() || chars.all { it.ch == ' ' }) return@mapNotNull null
        val pw = first.words.first().pw; val ph = first.words.first().ph
        val text = String(CharArray(chars.size) { chars[it].ch })
        val model = ParaModel(page, l, r, pw, ph, justify, pitch, chars, mlines, orig, glyphs, fonts, FallbackM)
        TextBlock(page, l, first.base - size * 0.95f, r, blk.last().base + size * 0.3f, text, pw, ph).also { it.seed = model }
    }
}
