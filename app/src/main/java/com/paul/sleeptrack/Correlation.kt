package com.paul.sleeptrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Une journée et la nuit qui l'a suivie. */
data class DayNight(val day: LocalDate, val steps: Double, val sleepHours: Double)

/**
 * Apparie chaque journée avec la nuit qui la suit. Une nuit est rattachée à sa date de
 * réveil : la nuit qui suit le jour `d` est donc celle enregistrée au jour `d + 1`.
 */
fun pairDaysWithNights(data: HealthData): List<DayNight> =
    data.steps.mapNotNull { (day, steps) ->
        data.nights[day.plusDays(1)]?.let {
            DayNight(day, steps.toDouble(), it.toMinutes() / 60.0)
        }
    }.sortedBy { it.day }

/** Coefficient de Pearson, ou null si l'échantillon est trop pauvre. */
fun pearson(pairs: List<DayNight>): Double? {
    if (pairs.size < 3) return null
    val n = pairs.size
    val mx = pairs.sumOf { it.steps } / n
    val my = pairs.sumOf { it.sleepHours } / n
    var sxy = 0.0
    var sxx = 0.0
    var syy = 0.0
    for (p in pairs) {
        val dx = p.steps - mx
        val dy = p.sleepHours - my
        sxy += dx * dy
        sxx += dx * dx
        syy += dy * dy
    }
    if (sxx == 0.0 || syy == 0.0) return null
    return sxy / sqrt(sxx * syy)
}

private const val ACTIVE_THRESHOLD = 8_000.0

data class CorrelationInsight(
    val r: Double,
    val count: Int,
    val afterActive: Duration?,
    val afterQuiet: Duration?,
) {
    /** Écart de sommeil entre les nuits qui suivent une journée active et les autres. */
    val gap: Duration?
        get() = if (afterActive != null && afterQuiet != null) afterActive - afterQuiet else null

    val strength: String
        get() = when {
            abs(r) < 0.10 -> "Aucun lien visible"
            abs(r) < 0.25 -> "Lien très faible"
            abs(r) < 0.40 -> "Lien modéré"
            else -> "Lien net"
        }
}

fun correlationInsight(data: HealthData): CorrelationInsight? {
    val pairs = pairDaysWithNights(data)
    val r = pearson(pairs) ?: return null
    val active = pairs.filter { it.steps >= ACTIVE_THRESHOLD }
    val quiet = pairs.filter { it.steps < ACTIVE_THRESHOLD }
    return CorrelationInsight(
        r = r,
        count = pairs.size,
        afterActive = active.takeIf { it.size >= 3 }?.let { meanSleep(it) },
        afterQuiet = quiet.takeIf { it.size >= 3 }?.let { meanSleep(it) },
    )
}

private fun meanSleep(pairs: List<DayNight>): Duration =
    Duration.ofMinutes((pairs.sumOf { it.sleepHours } / pairs.size * 60).toLong())

@Composable
fun CorrelationPanel(data: HealthData, showNotes: Boolean = true) {
    val pairs = pairDaysWithNights(data)
    val insight = correlationInsight(data)

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Activité et sommeil", color = Palette.text, fontWeight = FontWeight.SemiBold)

        if (insight == null || pairs.size < 10) {
            Text(
                "Il faut au moins une dizaine de journées avec des pas et la nuit qui suit " +
                    "pour comparer les deux (${pairs.size} pour l'instant).",
                color = Palette.muted,
                fontSize = 13.sp,
            )
            return@Column
        }

        Scatter(pairs)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${insight.strength} · r = %.2f".format(Locale.FRENCH, insight.r),
                color = if (abs(insight.r) < 0.10) Palette.muted else Palette.levels[3],
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text("${insight.count} paires", color = Palette.muted, fontSize = 12.sp)
        }

        val active = insight.afterActive
        val quiet = insight.afterQuiet
        if (active != null && quiet != null) {
            val gap = insight.gap!!
            val minutes = gap.toMinutes()
            Text(
                buildString {
                    append("Après une journée à 8 000 pas ou plus : ${formatDuration(active)} de sommeil, ")
                    append("contre ${formatDuration(quiet)} après une journée plus calme")
                    append(
                        when {
                            abs(minutes) < 6 -> " — soit la même chose."
                            minutes > 0 -> " — ${minutes} minutes de plus."
                            else -> " — ${-minutes} minutes de moins."
                        }
                    )
                },
                color = Palette.muted,
                fontSize = 13.sp,
            )
        }

        if (showNotes) {
            Text(
                "Un lien n'est pas une cause : une journée active et une bonne nuit peuvent " +
                    "simplement suivre la même semaine tranquille.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun Scatter(pairs: List<DayNight>) {
    val maxSteps = pairs.maxOf { it.steps }.coerceAtLeast(1.0)
    val minSleep = pairs.minOf { it.sleepHours }
    val maxSleep = pairs.maxOf { it.sleepHours }
    val sleepSpan = (maxSleep - minSleep).coerceAtLeast(0.5)
    val fit = leastSquares(pairs)

    Column {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val w = size.width
            val h = size.height
            // Repères horizontaux aux heures rondes.
            var hour = kotlin.math.ceil(minSleep).toInt()
            while (hour <= maxSleep) {
                val y = h - ((hour - minSleep) / sleepSpan * h).toFloat()
                drawLine(Palette.empty, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                hour++
            }
            for (p in pairs) {
                val x = (p.steps / maxSteps * w).toFloat()
                val y = h - ((p.sleepHours - minSleep) / sleepSpan * h).toFloat()
                drawCircle(
                    color = scaleForSleepHours(p.sleepHours).copy(alpha = 0.75f),
                    radius = 3.dp.toPx(),
                    center = Offset(x.coerceIn(0f, w), y.coerceIn(0f, h)),
                )
            }
            if (fit != null) {
                val (slope, intercept) = fit
                val y0 = h - ((intercept - minSleep) / sleepSpan * h).toFloat()
                val y1 = h - ((intercept + slope * maxSteps - minSleep) / sleepSpan * h).toFloat()
                drawLine(
                    Color.White.copy(alpha = 0.5f),
                    Offset(0f, y0.coerceIn(-h, 2 * h)),
                    Offset(w, y1.coerceIn(-h, 2 * h)),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("0 pas", color = Palette.muted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "sommeil ${formatDuration(Duration.ofMinutes((minSleep * 60).toLong()))}" +
                    " → ${formatDuration(Duration.ofMinutes((maxSleep * 60).toLong()))}",
                color = Palette.muted,
                fontSize = 10.sp,
            )
            Spacer(Modifier.weight(1f))
            Text("${formatSteps(maxSteps.toLong())} pas", color = Palette.muted, fontSize = 10.sp)
        }
    }
}

private fun scaleForSleepHours(hours: Double): Color =
    Scale(listOf(300.0, 360.0, 420.0, 480.0), false, Palette.levels, emptyList()).colorOf(hours * 60)

/** Droite de régression y = slope·x + intercept, ou null si x est constant. */
private fun leastSquares(pairs: List<DayNight>): Pair<Double, Double>? {
    val n = pairs.size
    if (n < 3) return null
    val mx = pairs.sumOf { it.steps } / n
    val my = pairs.sumOf { it.sleepHours } / n
    var sxy = 0.0
    var sxx = 0.0
    for (p in pairs) {
        sxy += (p.steps - mx) * (p.sleepHours - my)
        sxx += (p.steps - mx) * (p.steps - mx)
    }
    if (sxx == 0.0) return null
    val slope = sxy / sxx
    return slope to (my - slope * mx)
}
