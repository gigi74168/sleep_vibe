package com.paul.sleeptrack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.paul.sleeptrack.ui.theme.AubeDimens
import com.paul.sleeptrack.ui.theme.SleepColors
import com.paul.sleeptrack.ui.theme.cssBlurToRadius
import java.io.OutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Le rendu bitmap sert au widget et à l'image partagée : même grille que l'écran
// principal, mais dessinée avec android.graphics plutôt qu'avec Compose.

/**
 * Couleurs, polices et densité d'un rendu bitmap. En Classique, les polices système d'avant ;
 * en Aube, Young Serif et Figtree, embarquées dans l'app.
 */
class BitmapStyle(
    val colors: SleepColors,
    val density: Float,
    private val serif: Typeface? = null,
    private val sans: Typeface? = null,
) {
    /** Un texte ; en Classique, « sans-serif-medium » dès 500, « sans-serif » en dessous. */
    fun text(color: Color, size: Float, weight: Int = 400): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = size
        if (sans != null) {
            typeface = sans
            fontVariationSettings = "'wght' $weight"
            fontFeatureSettings = "tnum"
        } else {
            typeface = Typeface.create(if (weight >= 500) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
    }

    /** Un titre en Young Serif (Aube) ; en Classique, le texte en gras d'avant. */
    fun title(color: Color, size: Float): Paint = if (serif == null) {
        text(color, size, 600)
    } else {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = size
            typeface = serif
        }
    }
}

fun bitmapStyle(
    context: Context,
    colors: SleepColors,
    density: Float = context.resources.displayMetrics.density,
): BitmapStyle = if (colors.isAube) {
    BitmapStyle(
        colors,
        density,
        ResourcesCompat.getFont(context, R.font.young_serif),
        ResourcesCompat.getFont(context, R.font.figtree),
    )
} else {
    BitmapStyle(colors, density)
}

private fun fill(color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }

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
    style: BitmapStyle,
    today: LocalDate = LocalDate.now(),
    goals: Goals = Goals(),
): Bitmap {
    val c = style.colors
    val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(100), heightPx.coerceAtLeast(60), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val w = bmp.width.toFloat()
    val h = bmp.height.toFloat()
    // Aube : une carte couleur Surface aux coins de 28 dp ; Classique : le fond d'avant.
    val radius = if (c.isAube) minOf(AubeDimens.WidgetRadius.value * style.density, h / 2) else h * 0.12f
    canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, fill(if (c.isAube) c.surface else c.background))

    // Aube : assez de marge pour que les coins arrondis ne mordent pas sur les pastilles.
    val pad = if (c.isAube) maxOf(h * 0.09f, radius * 0.3f) else h * 0.09f
    // Sans en-tête quand la tuile est trop plate : sous ~55 dp en Classique (en pixels, comme
    // avant) ; en Aube sous 120 dp, ce qui laisse le 4×1 et le 2×1 sans en-tête.
    val compact = if (c.isAube) h / style.density < 120f else h < 170f
    // La taille du titre suit aussi la largeur, sinon un widget 2x2 ne peut rien afficher d'autre.
    val headerSize = if (compact) 0f else minOf(h * 0.15f, w * 0.09f).coerceIn(20f, 44f)
    val header = if (compact) 0f else headerSize * 1.6f
    val gridTop = pad + header
    val pitch = (h - pad - gridTop) / 7f
    val side = pitch * 0.82f
    val weeks = (((w - 2 * pad) / pitch).toInt()).coerceIn(3, 40)

    val series = data.series(metric)
    val scale = scaleFor(metric, goals, c.levels)
    val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val gridStart = lastMonday.minusWeeks((weeks - 1).toLong())
    val shown = series.filterKeys { it >= gridStart && !it.isAfter(today) }

    if (!compact) {
        val title = metric.label
        val summary = if (shown.isEmpty()) "aucune donnée" else {
            "moy. " + metric.format(shown.values.average())
        }
        val titlePaint = style.text(c.ink, headerSize, 600)
        canvas.drawText(title, pad, pad + headerSize, titlePaint)
        val summaryPaint = style.text(c.ink2, headerSize * 0.85f)
        val summaryX = w - pad - summaryPaint.measureText(summary)
        // Sur un widget étroit le résumé mordrait sur le titre : dans ce cas on le laisse tomber.
        if (summaryX > pad + titlePaint.measureText(title) + headerSize * 0.5f) {
            canvas.drawText(summary, summaryX, pad + headerSize, summaryPaint)
        }
    }

    val gridWidth = weeks * pitch
    val left = w - pad - gridWidth
    val brush = fill(Color.Transparent)
    for (col in 0 until weeks) {
        for (row in 0..6) {
            val day = gridStart.plusDays(col * 7L + row)
            if (c.isAube) {
                val cx = left + col * pitch + pitch / 2
                val cy = gridTop + row * pitch + pitch / 2
                val content = when {
                    day.isAfter(today) -> CellContent.Future
                    else -> shown[day]?.let { CellContent.Level(scale.levelOf(it)) } ?: CellContent.Empty
                }
                canvas.drawPastilleBitmap(cx, cy, side, content, c, style.density, brush)
                if (day == today) canvas.drawTodayMarkBitmap(cx, cy, side, c, style.density, brush)
                continue
            }
            if (day.isAfter(today)) continue
            val value = shown[day]
            val color = value?.let { scale.colorOf(it).toArgb() } ?: c.surface2.toArgb()
            canvas.cell(left + col * pitch + (pitch - side) / 2, gridTop + row * pitch + (pitch - side) / 2, side, color, brush)
        }
    }
    return bmp
}

/**
 * Carte annuelle complète, pour le partage. La mise en page est calculée une fois ; le dessin
 * peut se refaire sur n'importe quel Canvas, ce qui permet de rendre l'image par bandes.
 */
class YearCard(
    private val year: Int,
    private val metric: Metric,
    data: HealthData,
    goals: Goals,
    private val style: BitmapStyle,
    val width: Int,
) {
    private val c = style.colors
    private val series = data.series(metric)
    private val scale = scaleFor(metric, goals, c.levels)
    private val tiles = statTiles(metric, data, scale, goals, c.ink2)
    private val today = LocalDate.now()

    private val pad = width * 0.05f
    private val labelWidth = width * 0.035f
    private val gridStart = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val weeks = (ChronoUnit.DAYS.between(gridStart, LocalDate.of(year, 12, 31)) / 7 + 1).toInt()
    private val pitch = (width - 2 * pad - labelWidth) / weeks
    private val side = pitch * 0.82f

    private val titleSize = width * 0.045f
    private val subSize = width * 0.024f
    private val smallSize = width * 0.018f
    private val monthSize = width * 0.017f

    private val tileHeight = width * 0.085f
    private val headerBlock = pad + titleSize + subSize * 2.0f
    private val monthBlock = monthSize * 1.8f
    private val gridBlock = pitch * 7
    private val legendBlock = smallSize * 3.4f
    private val statsBlock = tileHeight * 2 + pad * 0.3f
    private val footerBlock = smallSize * 2.6f
    val height: Int = (headerBlock + monthBlock + gridBlock + legendBlock + statsBlock + footerBlock + pad).toInt()

    /** Un « dp » de la maquette d'Aube, dont l'image de référence fait 1 080 px de large. */
    private val u = width / 1080f

    fun draw(canvas: Canvas) {
        canvas.drawColor(c.background.toArgb())

        var y = pad + titleSize
        if (c.isAube) {
            // « Sommeil 2026 », l'année en terracotta.
            val name = style.title(c.ink, titleSize)
            val label = "${metric.label} "
            canvas.drawText(label, pad, y, name)
            canvas.drawText("$year", pad + name.measureText(label), y, style.title(c.accent, titleSize))
        } else {
            canvas.drawText("${metric.label} · $year", pad, y, style.text(c.ink, titleSize, 600))
        }

        y += subSize * 1.6f
        val subtitle = if (series.isEmpty()) {
            "aucune donnée"
        } else {
            "${metric.countLabel(series.size)} · moyenne ${metric.format(series.values.average())}"
        }
        canvas.drawText(subtitle, pad, y, style.text(c.ink2, subSize))

        // Mois
        y += monthBlock
        val axisWeight = if (c.isAube) 500 else 400
        val monthPaint = style.text(c.ink2, monthSize, axisWeight)
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
        val dayPaint = style.text(c.ink2, monthSize, axisWeight)
        listOf(0 to "Lun", 2 to "Mer", 4 to "Ven", 6 to "Dim").forEach { (row, label) ->
            canvas.drawText(label, pad, gridTop + row * pitch + side * 0.8f, dayPaint)
        }
        val brush = fill(Color.Transparent)
        for (col in 0 until weeks) {
            for (row in 0..6) {
                val day = gridStart.plusDays(col * 7L + row)
                if (day.year != year) continue
                if (c.isAube) {
                    val content = when {
                        day.isAfter(today) -> CellContent.Future
                        else -> series[day]?.let { CellContent.Level(scale.levelOf(it)) } ?: CellContent.Empty
                    }
                    canvas.drawPastilleBitmap(
                        pad + labelWidth + col * pitch + pitch / 2,
                        gridTop + row * pitch + pitch / 2,
                        side, content, c, u, brush,
                    )
                    continue
                }
                val color = series[day]?.let { scale.colorOf(it).toArgb() } ?: c.surface2.toArgb()
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
        val legendPaint = style.text(c.ink2, smallSize, axisWeight)
        if (c.isAube) {
            // Les pastilles de chaque niveau, le seuil centré dessous.
            val dot = smallSize * 1.2f
            val centerY = gridTop + gridBlock + smallSize
            var x = pad + labelWidth
            scale.labels.forEachIndexed { level, label ->
                val column = maxOf(dot, legendPaint.measureText(label))
                val cx = x + column / 2
                canvas.drawPastilleBitmap(cx, centerY, dot, CellContent.Level(level), c, u, brush)
                canvas.drawText(label, cx - legendPaint.measureText(label) / 2, centerY + smallSize * 1.9f, legendPaint)
                x += column + smallSize * 1.6f
            }
        } else {
            var x = pad + labelWidth
            scale.colors.zip(scale.labels).forEach { (color, label) ->
                canvas.cell(x, y - smallSize * 0.85f, smallSize, color.toArgb(), brush)
                x += smallSize * 1.4f
                canvas.drawText(label, x, y, legendPaint)
                x += legendPaint.measureText(label) + smallSize * 1.2f
            }
        }

        // Tuiles de statistiques
        y += smallSize * 1.6f
        val tileWidth = (width - 2 * pad - pad * 0.3f) / 2
        tiles.take(4).forEachIndexed { i, tile ->
            val tx = pad + (i % 2) * (tileWidth + pad * 0.3f)
            val ty = y + (i / 2) * (tileHeight + pad * 0.3f)
            val rect = RectF(tx, ty, tx + tileWidth, ty + tileHeight)
            if (c.isAube) {
                aubeTile(canvas, rect)
                // La valeur en encre ; le niveau passe par une pastille devant le libellé.
                var labelX = tx + smallSize
                if (tile.color != c.ink2) {
                    brush.color = tile.color.toArgb()
                    canvas.drawCircle(labelX + smallSize * 0.35f, ty + tileHeight * 0.38f - smallSize * 0.35f, smallSize * 0.35f, brush)
                    labelX += smallSize * 1.1f
                }
                canvas.drawText(tile.label, labelX, ty + tileHeight * 0.38f, style.text(c.ink2, smallSize))
                canvas.drawText(tile.value, tx + smallSize, ty + tileHeight * 0.82f, style.text(c.ink, subSize * 1.15f))
            } else {
                canvas.drawRoundRect(rect, width * 0.015f, width * 0.015f, fill(c.surface))
                canvas.drawText(tile.label, tx + smallSize, ty + tileHeight * 0.38f, style.text(c.ink2, smallSize))
                canvas.drawText(tile.value, tx + smallSize, ty + tileHeight * 0.82f, style.text(tile.color, subSize * 1.15f, 600))
            }
        }

        y += tileHeight * 2 + pad * 0.3f + smallSize * 1.6f
        canvas.drawText("Sleep Track · Health Connect", pad, y, style.text(if (c.isAube) c.ink3 else c.ink2, smallSize))
    }

    /** Une tuile blanche de 32 dp, avec l'ombre douce d'Aube (deux couches). */
    private fun aubeTile(canvas: Canvas, rect: RectF) {
        val r = 32 * u
        val paint = fill(c.surface)
        c.shadow?.let { shadow ->
            paint.setShadowLayer(cssBlurToRadius(28 * u), 0f, 10 * u, shadow.copy(alpha = 0.09f).toArgb())
            canvas.drawRoundRect(rect, r, r, paint)
            paint.setShadowLayer(cssBlurToRadius(2 * u), 0f, 1 * u, shadow.copy(alpha = 0.06f).toArgb())
        }
        canvas.drawRoundRect(rect, r, r, paint)
    }

    fun toBitmap(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }

    /**
     * Écrit l'image en PNG par bandes de [bandHeight] lignes : seule une bande tient en
     * mémoire, et la carte entière est redessinée, décalée, pour chacune.
     */
    fun writePng(out: OutputStream, bandHeight: Int = 256) {
        val band = Bitmap.createBitmap(width, bandHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(band)
        val pixels = IntArray(width * bandHeight)
        val png = PngStreamWriter(out, width, height)
        try {
            var top = 0
            while (top < height) {
                val rows = minOf(bandHeight, height - top)
                canvas.save()
                canvas.translate(0f, -top.toFloat())
                draw(canvas)
                canvas.restore()
                band.getPixels(pixels, 0, width, 0, 0, width, rows)
                for (r in 0 until rows) png.writeRow(pixels, r * width)
                top += rows
            }
            png.finish()
        } finally {
            band.recycle()
        }
    }
}
