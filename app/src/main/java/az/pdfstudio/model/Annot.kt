package az.pdfstudio.model

/** Koordinatlar səhifənin sol-yuxarısına görə 0..1 aralığındadır. */
data class Pt(val x: Float, val y: Float)

sealed interface Annot {
    val page: Int
    /** size = şrift ölçüsünün səhifə enina nisbəti */
    data class Text(override val page: Int, val x: Float, val y: Float, val text: String, val size: Float, val color: Int) : Annot
    data class Highlight(override val page: Int, val l: Float, val t: Float, val r: Float, val b: Float, val color: Int) : Annot
    /** Ağ düzbucaqlı – mövcud mətni "silmək" üçün */
    data class Cover(override val page: Int, val l: Float, val t: Float, val r: Float, val b: Float) : Annot
    data class Ink(override val page: Int, val pts: List<Pt>, val color: Int) : Annot
}

enum class Tool(val label: String) { VIEW("Oxu"), EDIT("Redaktə"), TEXT("Əlavə"), HIGHLIGHT("Vurğula"), DRAW("Çək"), COVER("Ört") }

/** PDF-in öz mətn qatından çıxarılmış abzas (redaktə üçün). Koordinatlar punkt (pt), sol-yuxarıdan. */
data class TextBlock(
    val page: Int,
    val l: Float, val t: Float, val r: Float, val b: Float,
    val text: String,
    val pageW: Float, val pageH: Float
) {
    /** Redaktə modeli (equals/hashCode-a daxil deyil) */
    var seed: ParaModel? = null
}
