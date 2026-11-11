package az.pdfstudio.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Abzasdakı bir simvol: orijinal şrift (resurs adı), orijinal kod baytları, ölçü, rəng, enlik. */
class GChar(
    var ch: Char, var font: String?, var bytes: ByteArray?, var size: Float, var color: Int,
    var adv: Float, var x: Float, var bold: Boolean, var italic: Boolean, var serif: Boolean,
    var trailing: Boolean = false,   // sətir sonu görünməz boşluq
    var cont: Boolean = false,       // liqaturanın davamı
    var fallback: Boolean = false    // orijinal şriftdə olmayan simvol (Noto ilə çəkilir)
) {
    fun copy() = GChar(ch, font, bytes, size, color, adv, x, bold, italic, serif, trailing, cont, fallback)
}

class GlyphDef(val bytes: ByteArray, val advEm: Float)
class FontInfo(val family: String, val bold: Boolean, val italic: Boolean, val serif: Boolean)
class OrigLine(val x: Float, val r: Float, val base: Float, val size: Float)
class MLine(var start: Int, var x: Float, var base: Float, val origIdx: Int, var dirty: Boolean) {
    fun copy() = MLine(start, x, base, origIdx, dirty)
}

interface FallbackMetrics { fun adv(ch: Char, size: Float, bold: Boolean, italic: Boolean, serif: Boolean): Float }

/**
 * Bir abzasın redaktə modeli. Dəyişməyən sətirlər PDF-də ƏLLƏNMİR (100% eyni qalır),
 * yalnız toxunulan sətirlər orijinal şriftlə yenidən yazılır.
 */
class ParaModel(
    val page: Int, val l: Float, val r: Float, val pageW: Float, val pageH: Float,
    val justify: Boolean, val pitch: Float,
    val chars: MutableList<GChar>, val lines: MutableList<MLine>, val orig: List<OrigLine>,
    val glyphs: Map<String, GlyphDef>, val fonts: Map<String, FontInfo>, val metrics: FallbackMetrics
) {
    fun deepCopy() = ParaModel(page, l, r, pageW, pageH, justify, pitch,
        chars.mapTo(ArrayList()) { it.copy() }, lines.mapTo(ArrayList()) { it.copy() }, orig, glyphs, fonts, metrics)

    fun flat(): String { val a = CharArray(chars.size) { chars[it].ch }; return String(a) }

    fun changed() = lines.any { it.dirty } || orig.indices.any { i -> lines.none { it.origIdx == i } }

    private fun lineEnd(j: Int) = if (j + 1 < lines.size) lines[j + 1].start else chars.size
    fun lineOf(i: Int): Int = max(0, lines.indexOfLast { it.start <= i })

    // ---------------- yerləşdirmə ----------------
    fun layoutLine(j: Int): FloatArray {
        val s = lines[j].start; val e = lineEnd(j)
        val out = FloatArray(max(0, e - s))
        if (!lines[j].dirty) { for (k in s until e) out[k - s] = chars[k].x; return out }
        var vis = e - s
        while (vis > 0 && chars[s + vis - 1].trailing) vis--
        var natural = 0f; var spaces = 0
        for (k in s until s + vis) { natural += chars[k].adv; if (chars[k].ch == ' ') spaces++ }
        val limit = r - lines[j].x
        val extra = if (justify && j < lines.size - 1 && spaces > 0 && natural < limit) (limit - natural) / spaces else 0f
        var x = lines[j].x
        for (k in s until e) {
            out[k - s] = x
            x += chars[k].adv + (if (chars[k].ch == ' ' && k < s + vis) extra else 0f)
        }
        return out
    }

    fun caret(i: Int): FloatArray {
        val idx = i.coerceIn(0, chars.size)
        val j = lineOf(idx); val s = lines[j].start; val xs = layoutLine(j); val k = idx - s
        val x = if (k < xs.size) xs[k] else if (xs.isNotEmpty()) xs.last() + chars[s + xs.size - 1].adv else lines[j].x
        val size = chars.getOrNull(min(idx, chars.size - 1))?.size ?: 10f
        return floatArrayOf(x, lines[j].base - size * 0.9f, lines[j].base + size * 0.25f)
    }

    fun hit(px: Float, py: Float): Int {
        var bj = 0; var bd = Float.MAX_VALUE
        lines.forEachIndexed { j, ln ->
            val sz = chars.getOrNull(ln.start)?.size ?: 10f
            val d = abs(ln.base - 0.3f * sz - py)
            if (d < bd) { bd = d; bj = j }
        }
        val s = lines[bj].start; val e = lineEnd(bj); val xs = layoutLine(bj)
        var vis = e - s
        while (vis > 0 && chars[s + vis - 1].trailing) vis--
        for (k in 0 until vis) if (px < xs[k] + chars[s + k].adv / 2f) return s + k
        return s + vis
    }

    fun inside(px: Float, py: Float): Boolean {
        val f = lines.first(); val la = lines.last()
        val sz = chars.getOrNull(f.start)?.size ?: 10f
        return px >= l - 8f && px <= r + 8f && py >= f.base - sz * 1.1f && py <= la.base + sz * 0.6f
    }

    fun wordRange(i: Int): Pair<Int, Int> {
        var s = i.coerceIn(0, chars.size); var e = s
        while (s > 0 && chars[s - 1].ch != ' ') s--
        while (e < chars.size && chars[e].ch != ' ' && !chars[e].trailing) e++
        return s to e
    }

    /** [x1, top, x2, bottom] siyahısı */
    fun selRects(from: Int, to: Int): List<FloatArray> {
        val res = ArrayList<FloatArray>()
        for (j in lines.indices) {
            val s = lines[j].start; val e = lineEnd(j)
            val a = max(from, s); val b = min(to, e)
            if (a >= b) continue
            val xs = layoutLine(j); val sz = chars[s].size
            var last = b - 1
            while (last >= a && chars[last].trailing) last--
            if (last < a) continue
            res.add(floatArrayOf(xs[a - s], lines[j].base - sz * 0.9f, xs[last - s] + chars[last].adv, lines[j].base + sz * 0.25f))
        }
        return res
    }

    // ---------------- redaktə ----------------
    private fun spaceAdv(src: GChar): Float {
        val d = src.font?.let { glyphs["$it| "] }
        return d?.let { it.advEm * src.size } ?: (src.size * 0.25f)
    }

    private fun glyphFor(font: String?, c: Char): Pair<String, GlyphDef>? {
        if (font == null) return null
        glyphs["$font|$c"]?.let { return font to it }
        val cur = fonts[font] ?: return null
        for ((n, fi) in fonts) if (fi.family == cur.family && fi.bold == cur.bold && fi.italic == cur.italic) {
            glyphs["$n|$c"]?.let { return n to it }
        }
        return null
    }

    private fun makeChar(c: Char, src: GChar): GChar {
        if (c == ' ') {
            val d = src.font?.let { glyphs["$it| "] }
            return GChar(' ', src.font, d?.bytes, src.size, src.color, spaceAdv(src), 0f, src.bold, src.italic, src.serif, fallback = false)
        }
        if (!src.fallback) glyphFor(src.font, c)?.let { (n, g) ->
            return GChar(c, n, g.bytes, src.size, src.color, g.advEm * src.size, 0f, src.bold, src.italic, src.serif)
        }
        return GChar(c, null, null, src.size, src.color, metrics.adv(c, src.size, src.bold, src.italic, src.serif),
            0f, src.bold, src.italic, src.serif, fallback = true)
    }

    fun replace(from0: Int, to0: Int, ins: String) {
        val from = from0.coerceIn(0, chars.size); val to = to0.coerceIn(from, chars.size)
        val src = (chars.getOrNull(from - 1) ?: chars.getOrNull(from))?.copy() ?: return
        if (src.trailing) src.trailing = false
        if (to > from) {
            lines[lineOf(from)].dirty = true
            val del = to - from
            repeat(del) { chars.removeAt(from) }
            val kept = ArrayList<MLine>()
            for (ln in lines) when {
                ln.start <= from -> kept.add(ln)
                ln.start < to -> { }                                  // ortada qalan sətir əvvəlkinə birləşir
                else -> { ln.start -= del; kept.add(ln) }
            }
            lines.clear(); lines.addAll(kept)
            var k = 0
            while (k < lines.size - 1) { if (lines[k].start >= lines[k + 1].start) lines.removeAt(k) else k++ }
            if (lines.isEmpty()) lines.add(MLine(0, l, 0f, -1, true))
        }
        val text = ins.filter { it != '\n' && it != '\r' }
        var j = lineOf(from)
        if (text.isNotEmpty()) {
            lines[j].dirty = true
            var pos = from
            for (c in text) { chars.add(pos, makeChar(c, src)); pos++ }
            for (ln in lines) if (ln.start > from) ln.start += text.length
        }
        reflow(j)
    }

    /** Sətir enindən artıq olan hissəni növbəti sətrə ötürür. */
    fun reflow(j0: Int) {
        var j = max(0, j0)
        while (j < lines.size) {
            if (!lines[j].dirty) { j++; continue }
            val s = lines[j].start; val e = lineEnd(j)
            var vis = e - s
            while (vis > 0 && chars[s + vis - 1].trailing) vis--
            val limit = r - lines[j].x
            var w = 0f
            for (k in s until s + vis) w += chars[k].adv
            if (w <= limit + 0.3f) { j++; continue }
            var acc = 0f; var brk = -1
            for (k in s until s + vis) { if (chars[k].ch == ' ' && k > s && acc <= limit) brk = k; acc += chars[k].adv }
            if (brk == -1) { j++; continue }
            if (e - 1 >= s && chars[e - 1].trailing) chars[e - 1].trailing = false
            chars[brk].trailing = true
            val cut = brk + 1
            if (j + 1 < lines.size) { lines[j + 1].start = cut; lines[j + 1].dirty = true }
            else lines.add(MLine(cut, l, lines[j].base + pitch, -1, true))
            lines[j].dirty = true
            j++
        }
    }

    fun setStyle(from: Int, to: Int, bold: Boolean?, italic: Boolean?, sizeDelta: Float?, color: Int?) {
        var firstLine = Int.MAX_VALUE
        for (i in from until min(to, chars.size)) {
            val c = chars[i]
            if (c.trailing || c.cont) continue
            val j = lineOf(i); lines[j].dirty = true; firstLine = min(firstLine, j)
            if (color != null) c.color = color
            if (sizeDelta != null) { val ns = (c.size + sizeDelta).coerceIn(4f, 96f); c.adv *= ns / c.size; c.size = ns }
            if (bold != null || italic != null) restyle(c, bold ?: c.bold, italic ?: c.italic)
        }
        if (firstLine != Int.MAX_VALUE) reflow(firstLine)
    }

    private fun restyle(c: GChar, b: Boolean, i2: Boolean) {
        if (c.ch == ' ') { c.bold = b; c.italic = i2; return }
        val cur = c.font?.let { fonts[it] }
        if (!c.fallback && cur != null) {
            for ((n, fi) in fonts) if (fi.family == cur.family && fi.bold == b && fi.italic == i2) {
                val g = glyphs["$n|${c.ch}"] ?: continue
                c.font = n; c.bytes = g.bytes; c.adv = g.advEm * c.size; c.bold = b; c.italic = i2
                return
            }
        }
        c.font = null; c.bytes = null; c.fallback = true; c.bold = b; c.italic = i2
        c.adv = metrics.adv(c.ch, c.size, b, i2, c.serif)
    }
}
