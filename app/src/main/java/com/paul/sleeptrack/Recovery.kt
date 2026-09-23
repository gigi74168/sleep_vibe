package com.paul.sleeptrack

import java.time.Duration
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Score de récupération d'un matin, de 0 à 100, et ses sous-scores. Une composante désactivée,
 * sans donnée ou sans ligne de base vaut null : elle ne compte pas dans la moyenne.
 */
data class RecoveryScore(
    val total: Int,
    val sleep: Int?,
    val hrv: Int?,
    val load: Int?,
)

/** Ce qui entre dans le score ; chaque composante se coupe dans les réglages. */
enum class RecoveryComponent(val label: String) {
    SLEEP("Sommeil"),
    HRV("VFC"),
    LOAD("Pas de la veille"),
}

/** La ligne de base : les semaines d'avant, pas le jour lui-même. */
const val DEFAULT_BASELINE_DAYS = 28
val BASELINE_CHOICES = listOf(14, 28, 56)
private const val MIN_BASELINE = 7

data class RecoveryConfig(
    val components: Set<RecoveryComponent> = RecoveryComponent.entries.toSet(),
    val baselineDays: Int = DEFAULT_BASELINE_DAYS,
    /** Seul l'objectif de sommeil entre dans le calcul : il cale la note de la nuit. */
    val sleepGoalMinutes: Int = Goals.DEFAULT_SLEEP_MINUTES,
)

/**
 * Un score par matin, rattaché comme la nuit à la date du réveil : la moyenne des composantes
 * actives. La VFC se compare à la ligne de base de chacun plutôt qu'à une norme : 40 ms est
 * excellent pour l'un, bas pour l'autre.
 */
fun recoveryScores(
    nights: Map<LocalDate, Duration>,
    steps: Map<LocalDate, Long>,
    hrv: Map<LocalDate, Double>,
    config: RecoveryConfig = RecoveryConfig(),
    today: LocalDate = LocalDate.now(),
): Map<LocalDate, RecoveryScore> {
    val active = config.components
    if (active.isEmpty()) return emptyMap()
    // La VFC se distribue de façon très asymétrique : on travaille sur son logarithme.
    val lnHrv = hrv.filterValues { it > 0 }.mapValues { ln(it.value) }
    val stepSeries = steps.mapValues { it.value.toDouble() }

    // Un matin se repère à sa nuit ; sans le sommeil, à sa VFC ; sans l'une ni l'autre, au
    // lendemain d'une journée de pas.
    val candidates = when {
        RecoveryComponent.SLEEP in active -> nights.keys
        RecoveryComponent.HRV in active -> lnHrv.keys
        else -> steps.keys.map { it.plusDays(1) }.filter { !it.isAfter(today) }
    }
    // Une seule donnée ne fait pas un score, sauf si on n'en a gardé qu'une.
    val needed = minOf(2, active.size)

    val out = mutableMapOf<LocalDate, RecoveryScore>()
    for (day in candidates) {
        val sleep = if (RecoveryComponent.SLEEP in active) {
            nights[day]?.let { sleepScore(it.toMinutes().toDouble(), config.sleepGoalMinutes) }
        } else {
            null
        }
        val hrvPart = if (RecoveryComponent.HRV in active) {
            lnHrv[day]?.let { value ->
                baseline(lnHrv, day, skip = 1, days = config.baselineDays)
                    ?.let { (mean, sd) -> zScore((value - mean) / maxOf(sd, 0.05)) }
            }
        } else {
            null
        }
        // La charge, c'est la journée d'hier, comparée aux semaines qui la précèdent.
        val loadPart = if (RecoveryComponent.LOAD in active) {
            stepSeries[day.minusDays(1)]?.let { value ->
                baseline(stepSeries, day, skip = 2, days = config.baselineDays)
                    ?.let { (mean, _) -> loadScore(value, mean) }
            }
        } else {
            null
        }

        val parts = listOfNotNull(sleep, hrvPart, loadPart)
        if (parts.size < needed) continue
        out[day] = RecoveryScore(
            total = parts.average().roundToInt().coerceIn(0, 100),
            sleep = sleep?.roundToInt(),
            hrv = hrvPart?.roundToInt(),
            load = loadPart?.roundToInt(),
        )
    }
    return out
}

/**
 * 0 à 3 h sous l'objectif, 100 à une heure au-dessus : 4 h → 0 et 8 h → 100 avec l'objectif
 * par défaut de 7 h, le haut de l'échelle de couleurs du sommeil.
 */
internal fun sleepScore(minutes: Double, goalMinutes: Int = Goals.DEFAULT_SLEEP_MINUTES): Double =
    ((minutes - (goalMinutes - 180)) / 240.0 * 100.0).coerceIn(0.0, 100.0)

/** Un jour dans la moyenne vaut 60, deux écarts-types au-dessus 100, trois en dessous 0. */
private fun zScore(z: Double): Double = (60.0 + 20.0 * z).coerceIn(0.0, 100.0)

/** Une journée ordinaire ne coûte rien ; au double de l'habitude, le score tombe à 40. */
internal fun loadScore(steps: Double, usual: Double): Double {
    if (usual <= 0) return 100.0
    val ratio = steps / usual
    return when {
        ratio <= 1.2 -> 100.0
        ratio >= 2.0 -> 40.0
        else -> 100.0 - (ratio - 1.2) / 0.8 * 60.0
    }
}

/** Moyenne et écart-type des [days] jours qui précèdent, [skip] jours avant [day]. */
private fun baseline(
    series: Map<LocalDate, Double>,
    day: LocalDate,
    skip: Int,
    days: Int,
): Pair<Double, Double>? {
    val values = (skip until skip + days).mapNotNull { series[day.minusDays(it.toLong())] }
    if (values.size < MIN_BASELINE) return null
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    return mean to sqrt(variance)
}
