package com.paul.sleeptrack

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// --------------------------------------------------------------------- Séries

/** Ce qui compte comme une bonne journée. Le seuil du sommeil est celui des réglages. */
data class Target(val label: String, val good: (Double) -> Boolean)

fun targetFor(metric: Metric, goalMinutes: Int): Target? = when (metric) {
    Metric.SLEEP -> Target(
        "nuits d'au moins ${formatDuration(Duration.ofMinutes(goalMinutes.toLong()))}"
    ) { it >= goalMinutes }
    Metric.RECOVERY -> Target("journées au vert ($RECOVERY_GOOD ou plus)") { it >= RECOVERY_GOOD }
    Metric.STEPS -> Target("journées à 10 000 pas ou plus") { it >= 10_000 }
    Metric.HEART -> Target("journées à 58 bpm ou moins") { it <= 58 }
    Metric.SCREEN -> Target("journées sous 3h d'écran") { it < 180 }
    // Le poids n'a pas de « bon » côté : une série n'y voudrait rien dire.
    Metric.WEIGHT -> null
}

data class Streaks(val current: Int, val best: Int, val reached: Int)

/**
 * Jours consécutifs au-dessus de l'objectif. Un jour sans donnée coupe la série : on préfère
 * une série honnête à une série qui se prolonge parce que la montre est restée sur la table.
 */
fun streaksOf(series: Map<LocalDate, Double>, good: (Double) -> Boolean, today: LocalDate): Streaks {
    val days = series.filterValues(good).keys.sorted()
    if (days.isEmpty()) return Streaks(0, 0, 0)

    var best = 0
    var run = 0
    var previous: LocalDate? = null
    for (day in days) {
        run = if (previous?.plusDays(1) == day) run + 1 else 1
        if (run > best) best = run
        previous = day
    }

    // La série « en cours » doit toucher aujourd'hui ou hier : la nuit d'aujourd'hui peut
    // ne pas encore être enregistrée.
    var cursor = days.last().takeIf { it == today || it == today.minusDays(1) }
    var current = 0
    while (cursor != null && series[cursor]?.let(good) == true) {
        current++
        cursor = cursor.minusDays(1)
    }
    return Streaks(current, best, days.size)
}

/**
 * Les deux tuiles de série, ou null si la métrique ne s'y prête pas (le poids) ou si
 * l'année n'a pas encore de quoi dire quelque chose.
 */
fun streakTiles(
    metric: Metric,
    data: HealthData,
    goalMinutes: Int,
    year: Int,
    today: LocalDate = LocalDate.now(),
): Pair<StatTile, StatTile>? {
    val target = targetFor(metric, goalMinutes) ?: return null
    val series = data.series(metric)
    if (series.size < 7) return null
    val streaks = streaksOf(series, target.good, today)
    if (streaks.best == 0) {
        return StatTile("Objectif atteint", metric.countLabel(0), Palette.muted) to
            StatTile("Meilleure série", "—", Palette.muted)
    }

    // Une « série en cours » n'a de sens que sur l'année qui court.
    val first = if (year == today.year) {
        StatTile(
            "Série en cours",
            if (streaks.current == 0) "—" else metric.countLabel(streaks.current),
            if (streaks.current > 0) Palette.levels.last() else Palette.muted,
        )
    } else {
        StatTile("Objectif atteint", metric.countLabel(streaks.reached), Palette.levels[3])
    }
    val best = StatTile("Meilleure série", metric.countLabel(streaks.best), Palette.levels.last())
    return first to best
}

// --------------------------------------------------------------- Semaine type

fun weekAverages(series: Map<LocalDate, Double>): Map<DayOfWeek, Double> =
    series.entries
        .groupBy { it.key.dayOfWeek }
        .mapValues { (_, entries) -> entries.sumOf { it.value } / entries.size }

/** Moyenne de chaque jour de la semaine : ce que la grille annuelle ne montre pas. */
@Composable
fun WeekProfile(metric: Metric, data: HealthData, scale: Scale, showNote: Boolean) {
    val series = data.series(metric)
    val averages = weekAverages(series)

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Semaine type", color = Palette.text, fontWeight = FontWeight.SemiBold)

        if (averages.size < 7 || series.size < 21) {
            Text(
                "Il faut environ trois semaines pour que la moyenne de chaque jour veuille dire " +
                    "quelque chose (${metric.countLabel(series.size)} pour l'instant).",
                color = Palette.muted,
                fontSize = 13.sp,
            )
            return@Column
        }

        val low = averages.minBy { it.value }
        val high = averages.maxBy { it.value }
        // Les barres comparent les jours entre eux : un plancher à zéro les rendrait
        // toutes identiques, l'écart réel se joue sur quelques dizaines de minutes.
        val span = high.value - low.value

        Row(
            Modifier.fillMaxWidth().height(104.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DayOfWeek.entries.forEach { day ->
                val value = averages.getValue(day)
                val fraction = if (span < 1e-9) 1f else (0.28 + 0.72 * (value - low.value) / span).toFloat()
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        compactValue(metric, value),
                        color = Palette.muted,
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .background(scale.colorOf(value), RoundedCornerShape(5.dp))
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        day.getDisplayName(TextStyle.NARROW, Locale.FRENCH).uppercase(),
                        color = Palette.muted,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        Text(
            weekSentence(metric, low.key, low.value, high.key, high.value),
            color = Palette.muted,
            fontSize = 13.sp,
        )

        if (showNote) {
            Text(
                "Les barres se comparent entre elles, pas à zéro : c'est l'écart entre tes jours " +
                    "qui est dessiné, pas leur valeur absolue.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

private fun weekSentence(
    metric: Metric,
    lowDay: DayOfWeek,
    lowValue: Double,
    highDay: DayOfWeek,
    highValue: Double,
): String {
    val low = dayName(lowDay)
    val high = dayName(highDay)
    val lowText = metric.format(lowValue)
    val highText = metric.format(highValue)
    return when (metric) {
        Metric.SLEEP -> {
            val gap = ((highValue - lowValue)).toLong()
            "Tes nuits les plus longues tombent le $high ($highText), les plus courtes le " +
                "$low ($lowText) — $gap minutes d'écart."
        }
        Metric.RECOVERY -> "Tu récupères le mieux le $high ($highText), le moins bien le $low ($lowText)."
        Metric.STEPS -> "Tu marches le plus le $high ($highText) et le moins le $low ($lowText)."
        Metric.HEART -> "Ton cœur au repos est au plus bas le $low ($lowText) et au plus haut " +
            "le $high ($highText)."
        Metric.SCREEN -> "Tu passes le plus de temps sur ton écran le $high ($highText), le moins " +
            "le $low ($lowText)."
        Metric.WEIGHT -> "Tu es au plus léger le $low ($lowText) et au plus lourd le $high ($highText)."
    }
}

private fun dayName(day: DayOfWeek): String =
    day.getDisplayName(TextStyle.FULL, Locale.FRENCH)

/** Valeur raccourcie pour tenir sous une barre large d'un septième d'écran. */
private fun compactValue(metric: Metric, value: Double): String = when (metric) {
    Metric.SLEEP -> formatDuration(Duration.ofMinutes(value.toLong()))
    Metric.RECOVERY -> "%.0f".format(value)
    Metric.STEPS -> if (value >= 1_000) "%.1fk".format(Locale.FRENCH, value / 1_000) else "%.0f".format(value)
    Metric.HEART -> "%.0f".format(value)
    Metric.WEIGHT -> "%.1f".format(Locale.FRENCH, value)
    Metric.SCREEN -> formatDuration(Duration.ofMinutes(value.toLong()))
}
