package com.paul.sleeptrack

import androidx.compose.ui.graphics.Color
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** Les séries quotidiennes : trois lues dans Health Connect, le temps d'écran lu dans Android. */
data class HealthData(
    val nights: Map<LocalDate, Duration> = emptyMap(),
    val steps: Map<LocalDate, Long> = emptyMap(),
    /** Minutes d'écran allumé et déverrouillé. */
    val screen: Map<LocalDate, Double> = emptyMap(),
    /** VFC nocturne (RMSSD, en ms), rattachée comme le sommeil à la date du réveil. */
    val hrv: Map<LocalDate, Double> = emptyMap(),
    /** Ce qui entre dans le score de récupération, tiré des réglages. */
    val recoveryConfig: RecoveryConfig = RecoveryConfig(),
    /**
     * Scores déjà calculés sur un historique plus large : une année filtrée garde ainsi, pour
     * ses premiers jours, la ligne de base prise en décembre. Null : calculés sur ces données.
     */
    private val precomputedRecovery: Map<LocalDate, RecoveryScore>? = null,
) {
    // Les séries servent partout (grille, tuiles, séries, semaine type, détail du jour,
    // widget) : converties une fois par jeu de données plutôt qu'à chaque appel.
    private val sleepSeries by lazy { nights.mapValues { it.value.toMinutes().toDouble() } }
    private val stepSeries by lazy { steps.mapValues { it.value.toDouble() } }
    private val recoverySeries by lazy { recovery.mapValues { it.value.total.toDouble() } }

    /** Score de récupération de chaque matin, jamais stocké : toujours recalculé. */
    val recovery: Map<LocalDate, RecoveryScore> by lazy {
        precomputedRecovery ?: recoveryScores(nights, steps, hrv, recoveryConfig)
    }

    /** Série normalisée : minutes, score, pas ou minutes d'écran selon la métrique. */
    fun series(metric: Metric): Map<LocalDate, Double> = when (metric) {
        Metric.SLEEP -> sleepSeries
        Metric.RECOVERY -> recoverySeries
        Metric.STEPS -> stepSeries
        Metric.SCREEN -> screen
    }

    fun isEmpty(): Boolean = nights.isEmpty() && steps.isEmpty() && screen.isEmpty() && hrv.isEmpty()

    /** Les mêmes séries, avec un score de récupération calculé selon [config]. */
    fun withRecovery(config: RecoveryConfig) = HealthData(nights, steps, screen, hrv, config)

    fun filterYear(year: Int) = filterDays { it.year == year }

    /** Les jours à partir de [from], pour l'accueil. */
    fun since(from: LocalDate) = filterDays { it >= from }

    // Les scores sont calculés avant de couper : ceux qui restent gardent leur ligne de base.
    private fun filterDays(keep: (LocalDate) -> Boolean) = HealthData(
        nights.filterKeys(keep),
        steps.filterKeys(keep),
        screen.filterKeys(keep),
        hrv.filterKeys(keep),
        recoveryConfig,
        recovery.filterKeys(keep),
    )

    operator fun plus(other: HealthData) = HealthData(
        nights + other.nights,
        steps + other.steps,
        screen + other.screen,
        hrv + other.hrv,
        recoveryConfig,
    )
}

enum class Metric(val label: String, val detailLabel: String) {
    SLEEP("Sommeil", "Sommeil"),
    RECOVERY("Récup", "Récupération"),
    STEPS("Pas", "Pas"),
    SCREEN("Écran", "Temps d'écran"),
}

fun Metric.format(value: Double): String = when (this) {
    Metric.SLEEP -> formatDuration(Duration.ofMinutes(value.toLong()))
    Metric.RECOVERY -> "%.0f".format(value)
    Metric.STEPS -> formatSteps(value.toLong())
    Metric.SCREEN -> formatDuration(Duration.ofMinutes(value.toLong()))
}

/** Unité employée par une somme de valeurs ; « 312 nuits », « 1 jour ». */
fun Metric.countLabel(n: Int): String {
    val unit = if (this == Metric.SLEEP) "nuit" else "jour"
    return "$n $unit" + if (n > 1) "s" else ""
}

/**
 * Les objectifs, réglables. Ils décident des séries, des tuiles et des couleurs : chaque
 * échelle se cale sur son objectif.
 */
data class Goals(
    val sleepMinutes: Int = DEFAULT_SLEEP_MINUTES,
    val steps: Int = DEFAULT_STEPS,
    val screenMinutes: Int = DEFAULT_SCREEN_MINUTES,
    val recoveryGood: Int = DEFAULT_RECOVERY_GOOD,
) {
    companion object {
        const val DEFAULT_SLEEP_MINUTES = 420
        const val DEFAULT_STEPS = 10_000
        const val DEFAULT_SCREEN_MINUTES = 180
        const val DEFAULT_RECOVERY_GOOD = 67
    }
}

/**
 * Les quatre seuils entre les cinq couleurs, du plus bas au plus haut. Avec les objectifs par
 * défaut, ce sont les seuils d'avant qu'ils soient réglables : 5/6/7/8 h, 3/6/8/10k pas,
 * 2/3/4/5 h d'écran, 34/50/67/80 de récupération.
 */
fun boundsFor(metric: Metric, goals: Goals): List<Double> = when (metric) {
    Metric.SLEEP -> goals.sleepMinutes.let { g -> listOf(g - 120.0, g - 60.0, g.toDouble(), g + 60.0) }
    Metric.RECOVERY -> goals.recoveryGood.let { g ->
        listOf(g - 33.0, g - 17.0, g.toDouble(), minOf(g + 13.0, 100.0))
    }
    // Multiplier avant de diviser : 0,3 × 10 000 donnerait 3 000,000…1 et non 3 000.
    Metric.STEPS -> listOf(3, 6, 8, 10).map { it * goals.steps / 10.0 }
    Metric.SCREEN -> listOf(2, 3, 4, 5).map { it * goals.screenMinutes / 3.0 }
}

/**
 * Cinq niveaux de couleur. Pour le sommeil, la récupération et les pas, plus c'est haut mieux
 * c'est ; pour le temps d'écran c'est l'inverse.
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

fun scaleFor(metric: Metric, goals: Goals): Scale {
    val bounds = boundsFor(metric, goals)
    // Libellés du plus bas au plus haut ; l'écran les lit à l'envers, du pire au meilleur.
    val labels = when (metric) {
        Metric.SLEEP, Metric.SCREEN -> rangeLabels(bounds.map { it.roundToInt() }, ::hoursText, "h")
        Metric.STEPS -> rangeLabels(bounds.map { it.roundToInt() }, ::thousandsText, "k")
        Metric.RECOVERY -> rangeLabels(bounds.map { it.roundToInt() }, { it.toString() }, "")
    }
    val reversed = metric == Metric.SCREEN
    return Scale(bounds, reversed, Palette.levels, if (reversed) labels.reversed() else labels)
}

/** « <5h », « 5-6h »… « 8h+ » : l'unité n'est écrite qu'une fois par libellé. */
private fun rangeLabels(bounds: List<Int>, text: (Int) -> String, unit: String): List<String> {
    // « 7h30 » porte déjà son « h » : on n'en ajoute pas un second.
    fun withUnit(value: Int) = text(value).let { if (unit == "h" && 'h' in it) it else it + unit }
    return listOf("<${withUnit(bounds[0])}") +
        bounds.zipWithNext { a, b -> "${text(a)}-${withUnit(b)}" } +
        "${withUnit(bounds.last())}+"
}

// 420 → « 7 », 450 → « 7h30 » : le « h » final vient de l'unité du libellé.
private fun hoursText(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60}" else "%dh%02d".format(minutes / 60, minutes % 60)

private fun thousandsText(steps: Int): String =
    if (steps % 1_000 == 0) "${steps / 1_000}" else "%.1f".format(Locale.FRENCH, steps / 1_000.0)

/** « 10k », « 7h30 » : un objectif écrit court, pour un libellé de tuile. */
fun shortGoal(metric: Metric, value: Int): String = when (metric) {
    Metric.SLEEP, Metric.SCREEN -> hoursText(value).let { if ('h' in it) it else "${it}h" }
    Metric.STEPS -> "${thousandsText(value)}k"
    Metric.RECOVERY -> "$value"
}

/** Une tuile de statistique, partagée entre l'écran principal et l'image exportée. */
data class StatTile(val label: String, val value: String, val color: Color)

fun statTiles(metric: Metric, data: HealthData, scale: Scale, goals: Goals): List<StatTile> {
    val series = data.series(metric)
    if (series.isEmpty()) return emptyList()
    val today = LocalDate.now()
    val average = series.values.average()
    val last7 = series.filterKeys { it > today.minusDays(7) }.values
    val recent = if (last7.isEmpty()) null else last7.average()

    fun tile(label: String, value: Double) = StatTile(label, metric.format(value), scale.colorOf(value))
    val recentTile = recent?.let { tile("7 derniers jours", it) }
        ?: StatTile("7 derniers jours", "—", Palette.muted)

    // La quatrième tuile compte les bons jours, selon l'objectif ; elle est verte s'il y en a.
    fun countTile(label: String, n: Int) =
        StatTile(label, "$n", if (n > 0) Palette.levels.last() else Palette.levels[0])

    return when (metric) {
        Metric.SLEEP -> {
            // Une heure sous l'objectif : le seuil du orange, « Nuits < 6h » par défaut.
            val short = goals.sleepMinutes - 60
            val count = series.values.count { it < short }
            listOf(
                tile("Moyenne", average),
                recentTile,
                tile("Record", series.values.max()),
                StatTile(
                    "Nuits < ${shortGoal(metric, short)}", "$count",
                    if (count > 0) Palette.levels[0] else Palette.levels.last(),
                ),
            )
        }
        Metric.RECOVERY -> listOf(
            tile("Moyenne", average),
            recentTile,
            tile("Meilleur", series.values.max()),
            countTile("Jours ≥ ${goals.recoveryGood}", series.values.count { it >= goals.recoveryGood }),
        )
        Metric.STEPS -> listOf(
            tile("Moyenne", average),
            recentTile,
            tile("Record", series.values.max()),
            countTile("Jours ≥ ${shortGoal(metric, goals.steps)}", series.values.count { it >= goals.steps }),
        )
        Metric.SCREEN -> listOf(
            tile("Moyenne", average),
            recentTile,
            tile("Plus sobre", series.values.min()),
            countTile(
                "Jours < ${shortGoal(metric, goals.screenMinutes)}",
                series.values.count { it < goals.screenMinutes },
            ),
        )
    }
}

fun formatDuration(d: Duration): String = "%dh%02d".format(d.toHours(), d.toMinutesPart())

fun formatSteps(steps: Long): String = "%,d".format(steps).replace(',', ' ')
