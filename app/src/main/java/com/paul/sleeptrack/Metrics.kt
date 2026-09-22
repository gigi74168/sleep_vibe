package com.paul.sleeptrack

import androidx.compose.ui.graphics.Color
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

/** Les séries quotidiennes : quatre lues dans Health Connect, le temps d'écran lu dans Android. */
data class HealthData(
    val nights: Map<LocalDate, Duration> = emptyMap(),
    val steps: Map<LocalDate, Long> = emptyMap(),
    val heart: Map<LocalDate, Double> = emptyMap(),
    val weight: Map<LocalDate, Double> = emptyMap(),
    /** Minutes d'écran allumé et déverrouillé. */
    val screen: Map<LocalDate, Double> = emptyMap(),
) {
    // Les séries servent partout (grille, tuiles, séries, semaine type, détail du jour,
    // widget) : converties une fois par jeu de données plutôt qu'à chaque appel.
    private val sleepSeries by lazy { nights.mapValues { it.value.toMinutes().toDouble() } }
    private val stepSeries by lazy { steps.mapValues { it.value.toDouble() } }

    /** Série normalisée : minutes, pas, bpm, kg ou minutes d'écran selon la métrique. */
    fun series(metric: Metric): Map<LocalDate, Double> = when (metric) {
        Metric.SLEEP -> sleepSeries
        Metric.STEPS -> stepSeries
        Metric.HEART -> heart
        Metric.WEIGHT -> weight
        Metric.SCREEN -> screen
    }

    fun isEmpty(): Boolean =
        nights.isEmpty() && steps.isEmpty() && heart.isEmpty() && weight.isEmpty() && screen.isEmpty()

    fun filterYear(year: Int) = HealthData(
        nights.filterKeys { it.year == year },
        steps.filterKeys { it.year == year },
        heart.filterKeys { it.year == year },
        weight.filterKeys { it.year == year },
        screen.filterKeys { it.year == year },
    )

    operator fun plus(other: HealthData) = HealthData(
        nights + other.nights,
        steps + other.steps,
        heart + other.heart,
        weight + other.weight,
        screen + other.screen,
    )
}

enum class Metric(val label: String, val detailLabel: String, val canHide: Boolean = true) {
    SLEEP("Sommeil", "Sommeil", canHide = false),
    STEPS("Pas", "Pas"),
    HEART("Cœur", "Cœur au repos"),
    WEIGHT("Poids", "Poids"),
    SCREEN("Écran", "Temps d'écran"),
}

fun Metric.format(value: Double): String = when (this) {
    Metric.SLEEP -> formatDuration(Duration.ofMinutes(value.toLong()))
    Metric.STEPS -> formatSteps(value.toLong())
    Metric.HEART -> "%.0f bpm".format(value)
    Metric.WEIGHT -> "%.1f kg".format(Locale.FRENCH, value)
    Metric.SCREEN -> formatDuration(Duration.ofMinutes(value.toLong()))
}

/** Unité employée par une somme de valeurs ; « 312 nuits », « 1 jour ». */
fun Metric.countLabel(n: Int): String {
    val unit = if (this == Metric.SLEEP) "nuit" else "jour"
    return "$n $unit" + if (n > 1) "s" else ""
}

/**
 * Cinq niveaux de couleur. Pour le sommeil et les pas, plus c'est haut mieux c'est ;
 * pour le cœur au repos c'est l'inverse ; le poids n'a pas de « mieux » et reçoit
 * un dégradé neutre calé sur les quintiles de l'année affichée.
 */
class Scale(
    private val bounds: List<Double>,
    private val reversed: Boolean,
    val colors: List<Color>,
    val labels: List<String>,
) {
    fun levelOf(value: Double): Int {
        val i = bounds.count { value >= it }
        return if (reversed) bounds.size - i else i
    }

    fun colorOf(value: Double): Color = colors[levelOf(value)]
}

fun scaleFor(metric: Metric, data: HealthData): Scale = when (metric) {
    Metric.SLEEP -> Scale(
        listOf(300.0, 360.0, 420.0, 480.0), false, Palette.levels,
        listOf("<5h", "5-6h", "6-7h", "7-8h", "8h+"),
    )
    Metric.STEPS -> Scale(
        listOf(3_000.0, 6_000.0, 8_000.0, 10_000.0), false, Palette.levels,
        listOf("<3k", "3-6k", "6-8k", "8-10k", "10k+"),
    )
    Metric.HEART -> Scale(
        listOf(52.0, 58.0, 64.0, 70.0), true, Palette.levels,
        listOf("70+", "64-70", "58-64", "52-58", "<52"),
    )
    Metric.WEIGHT -> weightScale(data.weight.values)
    // Moins il y en a, mieux c'est : l'échelle est inversée, comme celle du cœur.
    Metric.SCREEN -> Scale(
        listOf(120.0, 180.0, 240.0, 300.0), true, Palette.levels,
        listOf("5h+", "4-5h", "3-4h", "2-3h", "<2h"),
    )
}

private fun weightScale(values: Collection<Double>): Scale {
    val sorted = values.sorted()
    if (sorted.size < 5) {
        return Scale(listOf(0.0, 0.0, 0.0, 0.0), false, Palette.weightRamp, List(5) { "—" })
    }
    val bounds = (1..4).map { sorted[sorted.size * it / 5] }
    val decimals = if (sorted.last() - sorted.first() >= 5) 0 else 1
    fun fmt(v: Double) = "%.${decimals}f".format(Locale.FRENCH, v)
    val labels = listOf(
        "<${fmt(bounds[0])}",
        fmt(bounds[0]),
        fmt(bounds[1]),
        fmt(bounds[2]),
        "${fmt(bounds[3])}+",
    )
    return Scale(bounds, false, Palette.weightRamp, labels)
}

/** Une tuile de statistique, partagée entre l'écran principal et l'image exportée. */
data class StatTile(val label: String, val value: String, val color: Color)

fun statTiles(metric: Metric, data: HealthData, scale: Scale): List<StatTile> {
    val series = data.series(metric)
    if (series.isEmpty()) return emptyList()
    val today = LocalDate.now()
    val average = series.values.average()
    val last7 = series.filterKeys { it > today.minusDays(7) }.values
    val recent = if (last7.isEmpty()) null else last7.average()

    fun tile(label: String, value: Double) = StatTile(label, metric.format(value), scale.colorOf(value))
    val recentTile = recent?.let { tile("7 derniers jours", it) }
        ?: StatTile("7 derniers jours", "—", Palette.muted)

    return when (metric) {
        Metric.SLEEP -> {
            val short = series.values.count { it < 360 }
            listOf(
                tile("Moyenne", average),
                recentTile,
                tile("Record", series.values.max()),
                StatTile(
                    "Nuits < 6h", "$short",
                    if (short > 0) Palette.levels[0] else Palette.levels.last(),
                ),
            )
        }
        Metric.STEPS -> {
            val goalDays = series.values.count { it >= 10_000 }
            listOf(
                tile("Moyenne", average),
                recentTile,
                tile("Record", series.values.max()),
                StatTile(
                    "Jours ≥ 10k", "$goalDays",
                    if (goalDays > 0) Palette.levels.last() else Palette.levels[0],
                ),
            )
        }
        Metric.HEART -> listOf(
            tile("Moyenne", average),
            recentTile,
            tile("Plus bas", series.values.min()),
            trendTile(metric, series, "Tendance 30j", lowerIsBetter = true),
        )
        Metric.SCREEN -> {
            val light = series.values.count { it < 180 }
            listOf(
                tile("Moyenne", average),
                recentTile,
                tile("Plus sobre", series.values.min()),
                StatTile(
                    "Jours < 3h", "$light",
                    if (light > 0) Palette.levels.last() else Palette.levels[0],
                ),
            )
        }
        Metric.WEIGHT -> {
            val latest = series.maxByOrNull { it.key }!!.value
            listOf(
                StatTile("Dernière pesée", metric.format(latest), Palette.text),
                tile("Moyenne", average),
                StatTile(
                    "Amplitude",
                    "%.1f kg".format(Locale.FRENCH, series.values.max() - series.values.min()),
                    Palette.text,
                ),
                trendTile(metric, series, "Tendance 30j", lowerIsBetter = false),
            )
        }
    }
}

/** Écart entre la moyenne des 30 derniers jours et celle des 30 précédents. */
private fun trendTile(
    metric: Metric,
    series: Map<LocalDate, Double>,
    label: String,
    lowerIsBetter: Boolean,
): StatTile {
    val today = LocalDate.now()
    val recent = series.filterKeys { it > today.minusDays(30) }.values
    val previous = series.filterKeys { it <= today.minusDays(30) && it > today.minusDays(60) }.values
    if (recent.size < 3 || previous.size < 3) return StatTile(label, "—", Palette.muted)
    val delta = recent.average() - previous.average()
    val unit = if (metric == Metric.WEIGHT) "kg" else "bpm"
    val color = when {
        !lowerIsBetter -> Palette.text
        delta <= -0.5 -> Palette.levels.last()
        delta >= 0.5 -> Palette.levels[0]
        else -> Palette.muted
    }
    return StatTile(label, "%+.1f %s".format(Locale.FRENCH, delta, unit), color)
}

fun formatDuration(d: Duration): String = "%dh%02d".format(d.toHours(), d.toMinutesPart())

fun formatSteps(steps: Long): String = "%,d".format(steps).replace(',', ' ')
