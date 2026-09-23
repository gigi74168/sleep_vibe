package com.paul.sleeptrack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Le rendu bitmap sert au widget et à l'image partagée : même grille que l'écran
// principal, mais dessinée avec android.graphics plutôt qu'avec Compose.

private fun paint(color: Int, size: Float = 0f, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    if (size > 0f) {
        textSize = size
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
}

// Un seul pinceau par image, recoloré à chaque case : un Paint par case en créait
// plusieurs centaines par dessin, chacun avec sa mémoire native.
private fun Canvas.cell(x: Float, y: Float, side: Float, color: Int, brush: Paint) {
    brush.color = color
    drawRoundRect(RectF(x, y, x + side, y + side), side * 0.3f, side * 0.3f, brush)
}

/** Bande des dernières semaines, pour le widget. */
fun renderStrip(
    metric: Metric,
    data: HealthData,
    widthPx: Int,
    heightPx: Int,
    today: LocalDate = LocalDate.now(),
    /** Le fond du widget ; l'accueil, qui pose la bande sur ses cartes, en prend la couleur. */
    background: Int = Palette.bg.toArgb(),
    goals: Goals = Goals(),
): Bitmap {
    val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(100), heightPx.coerceAtLeast(60), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val w = bmp.width.toFloat()
    val h = bmp.height.toFloat()
    val radius = h * 0.12f
    canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, paint(background))

    val pad = h * 0.09f
    // Sous ~55dp de haut, l'en-tête mangerait la moitié de la tuile : la grille seule.
    val compact = h < 170f
    // La taille du titre suit aussi la largeur, sinon un widget 2x2 ne peut rien afficher d'autre.
    val headerSize = if (compact) 0f else minOf(h * 0.15f, w * 0.09f).coerceIn(20f, 44f)
    val header = if (compact) 0f else headerSize * 1.6f
    val gridTop = pad + header
    val pitch = (h - pad - gridTop) / 7f
    val side = pitch * 0.82f
    val weeks = (((w - 2 * pad) / pitch).toInt()).coerceIn(3, 40)

    val series = data.series(metric)
    val scale = scaleFor(metric, goals)
    val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val gridStart = lastMonday.minusWeeks((weeks - 1).toLong())
    val shown = series.filterKeys { it >= gridStart && !it.isAfter(today) }

    if (!compact) {
        val title = metric.label
        val summary = if (shown.isEmpty()) "aucune donnée" else {
            "moy. " + metric.format(shown.values.average())
        }
        val titlePaint = paint(Palette.text.toArgb(), headerSize, bold = true)
        canvas.drawText(title, pad, pad + headerSize, titlePaint)
        val summaryPaint = paint(Palette.muted.toArgb(), headerSize * 0.85f)
        val summaryX = w - pad - summaryPaint.measureText(summary)
        // Sur un widget étroit le résumé mordrait sur le titre : dans ce cas on le laisse tomber.
        if (summaryX > pad + titlePaint.measureText(title) + headerSize * 0.5f) {
            canvas.drawText(summary, summaryX, pad + headerSize, summaryPaint)
        }
    }

    val gridWidth = weeks * pitch
    val left = w - pad - gridWidth
    val brush = paint(0)
    for (col in 0 until weeks) {
        for (row in 0..6) {
            val day = gridStart.plusDays(col * 7L + row)
            if (day.isAfter(today)) continue
            val value = shown[day]
            val color = value?.let { scale.colorOf(it).toArgb() } ?: Palette.empty.toArgb()
            canvas.cell(left + col * pitch + (pitch - side) / 2, gridTop + row * pitch + (pitch - side) / 2, side, color, brush)
        }
    }
    return bmp
}

/** Carte annuelle complète, pour le partage. */
fun renderYearCard(
    year: Int,
    metric: Metric,
    data: HealthData,
    goals: Goals,
    widthPx: Int = 1400,
): Bitmap {
    val series = data.series(metric)
    val scale = scaleFor(metric, goals)
    val pad = widthPx * 0.05f
    val labelWidth = widthPx * 0.035f

    val gridStart = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = (ChronoUnit.DAYS.between(gridStart, LocalDate.of(year, 12, 31)) / 7 + 1).toInt()
    val pitch = (widthPx - 2 * pad - labelWidth) / weeks
    val side = pitch * 0.82f

    val titleSize = widthPx * 0.045f
    val subSize = widthPx * 0.024f
    val smallSize = widthPx * 0.018f
    val monthSize = widthPx * 0.017f

    val tileHeight = widthPx * 0.085f
    val headerBlock = pad + titleSize + subSize * 2.0f
    val monthBlock = monthSize * 1.8f
    val gridBlock = pitch * 7
    val legendBlock = smallSize * 3.4f
    val statsBlock = tileHeight * 2 + pad * 0.3f
    val footerBlock = smallSize * 2.6f
    val height = (headerBlock + monthBlock + gridBlock + legendBlock + statsBlock + footerBlock + pad).toInt()

    val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Palette.bg.toArgb())

    var y = pad + titleSize
    canvas.drawText("${metric.label} · $year", pad, y, paint(Palette.text.toArgb(), titleSize, bold = true))

    y += subSize * 1.6f
    val subtitle = if (series.isEmpty()) {
        "aucune donnée"
    } else {
        "${metric.countLabel(series.size)} · moyenne ${metric.format(series.values.average())}"
    }
    canvas.drawText(subtitle, pad, y, paint(Palette.muted.toArgb(), subSize))

    // Mois
    y += monthBlock
    val monthPaint = paint(Palette.muted.toArgb(), monthSize)
    for (month in 1..12) {
        val col = (ChronoUnit.DAYS.between(gridStart, LocalDate.of(year, month, 1)) / 7).toInt()
        val name = LocalDate.of(year, month, 1).month
            .getDisplayName(TextStyle.SHORT, Locale.FRENCH)
            .take(3)
            .replaceFirstChar { it.uppercase() }
        canvas.drawText(name, pad + labelWidth + col * pitch, y, monthPaint)
    }

    // Grille
    val gridTop = y + monthSize * 0.6f
    val dayPaint = paint(Palette.muted.toArgb(), monthSize)
    listOf(0 to "Lun", 2 to "Mer", 4 to "Ven", 6 to "Dim").forEach { (row, label) ->
        canvas.drawText(label, pad, gridTop + row * pitch + side * 0.8f, dayPaint)
    }
    val brush = paint(0)
    for (col in 0 until weeks) {
        for (row in 0..6) {
            val day = gridStart.plusDays(col * 7L + row)
            if (day.year != year) continue
            val color = series[day]?.let { scale.colorOf(it).toArgb() } ?: Palette.empty.toArgb()
            canvas.cell(
                pad + labelWidth + col * pitch + (pitch - side) / 2,
                gridTop + row * pitch + (pitch - side) / 2,
                side,
                color,
                brush,
            )
        }
    }

    // Légende
    y = gridTop + gridBlock + smallSize * 1.8f
    val legendPaint = paint(Palette.muted.toArgb(), smallSize)
    var x = pad + labelWidth
    scale.colors.zip(scale.labels).forEach { (color, label) ->
        canvas.cell(x, y - smallSize * 0.85f, smallSize, color.toArgb(), brush)
        x += smallSize * 1.4f
        canvas.drawText(label, x, y, legendPaint)
        x += legendPaint.measureText(label) + smallSize * 1.2f
    }

    // Tuiles de statistiques
    y += smallSize * 1.6f
    val tiles = statTiles(metric, data, scale, goals)
    val tileWidth = (widthPx - 2 * pad - pad * 0.3f) / 2
    tiles.take(4).forEachIndexed { i, tile ->
        val tx = pad + (i % 2) * (tileWidth + pad * 0.3f)
        val ty = y + (i / 2) * (tileHeight + pad * 0.3f)
        canvas.drawRoundRect(
            RectF(tx, ty, tx + tileWidth, ty + tileHeight),
            widthPx * 0.015f, widthPx * 0.015f,
            paint(Palette.card.toArgb()),
        )
        canvas.drawText(tile.label, tx + smallSize, ty + tileHeight * 0.38f, paint(Palette.muted.toArgb(), smallSize))
        canvas.drawText(
            tile.value,
            tx + smallSize,
            ty + tileHeight * 0.82f,
            paint(tile.color.toArgb(), subSize * 1.15f, bold = true),
        )
    }

    y += tileHeight * 2 + pad * 0.3f + smallSize * 1.6f
    canvas.drawText("Sleep Track · Health Connect", pad, y, paint(Palette.muted.toArgb(), smallSize))
    return bmp
}
