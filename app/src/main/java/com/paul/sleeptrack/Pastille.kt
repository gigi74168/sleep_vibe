package com.paul.sleeptrack

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.paul.sleeptrack.ui.theme.SleepColors

/**
 * Une case d'Aube : une pastille ronde centrée dans sa cellule carrée. Le diamètre code le
 * niveau une seconde fois, après la couleur, pour qui distingue mal les teintes ; il reste
 * donc actif même quand les cases sont minuscules.
 */
object Pastille {
    /** Diamètre de chaque niveau (0 = loin de l'objectif, 4 = à l'objectif), en fraction du côté. */
    val DIAMETERS = floatArrayOf(0.56f, 0.68f, 0.80f, 0.90f, 1.00f)

    /** En dessous de 8 dp, l'anneau des niveaux 1 et 2 ne se verrait plus. */
    const val RING_MIN_SIDE_DP = 8f
    /** Niveaux 1 et 2 : disque plein jusqu'à 84 % du rayon, anneau de 85 à 97 %. */
    const val FILL_TO = 0.84f
    const val RING_FROM = 0.85f
    const val RING_TO = 0.97f
    /** Pas de donnée : anneau seul, de 75 à 95 % de la demi-cellule. */
    const val EMPTY_FROM = 0.75f
    const val EMPTY_TO = 0.95f
    /** Jour à venir : un point de 32 % de la demi-cellule. */
    const val FUTURE_DOT = 0.32f

    const val TODAY_STROKE_DP = 1.5f
    const val TODAY_OFFSET_DP = 1f
    const val SELECTED_STROKE_DP = 2f
    const val SELECTED_OFFSET_DP = 1.5f
    const val SELECTED_OFFSET_SMALL_DP = 1f

    fun radius(level: Int, side: Float): Float = side * DIAMETERS[level] / 2f

    fun hasRing(level: Int, sideDp: Float): Boolean = level <= 1 && sideDp >= RING_MIN_SIDE_DP

    fun selectedOffsetDp(sideDp: Float): Float =
        if (sideDp < RING_MIN_SIDE_DP) SELECTED_OFFSET_SMALL_DP else SELECTED_OFFSET_DP

    /** Rayon du trait d'un repère (aujourd'hui, sélection) posé [offset] au-delà de la cellule. */
    fun markRadius(side: Float, offset: Float, stroke: Float): Float = side / 2f + offset + stroke / 2f
}

/** Ce qu'une case montre : un niveau, rien (jour sans donnée) ou un jour à venir. */
sealed interface CellContent {
    data class Level(val level: Int) : CellContent
    data object Empty : CellContent
    data object Future : CellContent
}

/**
 * Dessine une pastille de côté [side] (en pixels) autour de [center]. [scale] agrandit ou
 * rétrécit la pastille seule, pour le rebond de la sélection.
 */
fun DrawScope.drawPastille(center: Offset, side: Float, content: CellContent, c: SleepColors, scale: Float = 1f) {
    val half = side / 2f
    val sideDp = side / density
    when (content) {
        is CellContent.Level -> {
            val r = Pastille.radius(content.level, side) * scale
            val color = c.levels[content.level]
            if (Pastille.hasRing(content.level, sideDp)) {
                drawCircle(color, r * Pastille.FILL_TO, center)
                drawRing(c.rings[content.level], center, r * Pastille.RING_FROM, r * Pastille.RING_TO)
            } else {
                drawCircle(color, r, center)
            }
        }
        CellContent.Empty -> drawRing(c.emptyRing, center, half * Pastille.EMPTY_FROM, half * Pastille.EMPTY_TO)
        CellContent.Future -> drawCircle(c.outline, half * Pastille.FUTURE_DOT, center)
    }
}

/** Le repère d'aujourd'hui : un cercle accent, un peu en dehors de la cellule. */
fun DrawScope.drawTodayMark(center: Offset, side: Float, c: SleepColors) {
    val stroke = Pastille.TODAY_STROKE_DP.dp.toPx()
    val r = Pastille.markRadius(side, Pastille.TODAY_OFFSET_DP.dp.toPx(), stroke)
    drawCircle(c.today, r, center, style = Stroke(stroke))
}

/** Le repère de la case choisie : un cercle couleur encre. */
fun DrawScope.drawSelectionMark(center: Offset, side: Float, c: SleepColors, scale: Float = 1f) {
    val stroke = Pastille.SELECTED_STROKE_DP.dp.toPx()
    val offset = Pastille.selectedOffsetDp(side / density).dp.toPx()
    drawCircle(c.selection, Pastille.markRadius(side, offset, stroke) * scale, center, style = Stroke(stroke))
}

private fun DrawScope.drawRing(color: androidx.compose.ui.graphics.Color, center: Offset, from: Float, to: Float) {
    val width = to - from
    if (width <= 0f) return
    drawCircle(color, (from + to) / 2f, center, style = Stroke(width))
}
