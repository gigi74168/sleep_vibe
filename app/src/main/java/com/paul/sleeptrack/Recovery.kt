package com.paul.sleeptrack

import java.time.Duration
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Score de récupération d'un matin, de 0 à 100, et ses sous-scores. Une composante sans
 * donnée ou sans ligne de base vaut null : elle ne compte pas, les autres se partagent son poids.
 */
data class RecoveryScore(
    val total: Int,
    val sleep: Int?,
    val hrv: Int?,
    val heart: Int?,
    val load: Int?,
)

/** Seuils du cœur au repos, partagés avec l'échelle de couleurs de la grille. */
val HEART_BOUNDS = listOf(52.0, 58.0, 64.0, 70.0)

/** Seuils du score, du rouge au vert vif ; « au vert » à partir de [RECOVERY_GOOD]. */
val RECOVERY_BOUNDS = listOf(34.0, 50.0, 67.0, 80.0)
const val RECOVERY_GOOD = 67

/** La ligne de base : les quatre semaines d'avant, pas celle du jour lui-même. */
const val RECOVERY_BASELINE_DAYS = 28
private const val MIN_BASELINE = 7

private const val WEIGHT_SLEEP = 0.30
private const val WEIGHT_HRV = 0.35
private const val WEIGHT_HEART = 0.20
private const val WEIGHT_LOAD = 0.15

/**
 * Un score par nuit connue, rattaché comme elle à la date du réveil. La VFC et le cœur se
 * comparent à la ligne de base de chacun plutôt qu'à des normes : une VFC de 40 ms est
 * excellente pour l'un, basse pour l'autre.
 */
fun recoveryScores(
    nights: Map<LocalDate, Duration>,
    steps: Map<LocalDate, Long>,
    heart: Map<LocalDate, Double>,
    hrv: Map<LocalDate, Double>,
): Map<LocalDate, RecoveryScore> {
    // La VFC se distribue de façon très asymétrique : on travaille sur son logarithme.
    val lnHrv = hrv.filterValues { it > 0 }.mapValues { ln(it.value) }
    val stepSeries = steps.mapValues { it.value.toDouble() }

    val out = mutableMapOf<LocalDate, RecoveryScore>()
    for ((day, night) in nights) {
        val sleep = sleepScore(night.toMinutes().toDouble())
        val hrvPart = lnHrv[day]?.let { value ->
            baseline(lnHrv, day, skip = 1)?.let { (mean, sd) -> zScore((value - mean) / maxOf(sd, 0.05)) }
        }
        val heartPart = heart[day]?.let { value ->
            baseline(heart, day, skip = 1)
                ?.let { (mean, sd) -> zScore((mean - value) / maxOf(sd, 1.0)) }
                ?: absoluteHeartScore(value)
        }
        // La charge, c'est la journée d'hier, comparée aux quatre semaines qui la précèdent.
        val loadPart = stepSeries[day.minusDays(1)]?.let { value ->
            baseline(stepSeries, day, skip = 2)?.let { (mean, _) -> loadScore(value, mean) }
        }

        val parts = listOfNotNull(
            WEIGHT_SLEEP to sleep,
            hrvPart?.let { WEIGHT_HRV to it },
            heartPart?.let { WEIGHT_HEART to it },
            loadPart?.let { WEIGHT_LOAD to it },
        )
        // La nuit seule ne fait pas un score de récupération, juste une durée repeinte.
        if (parts.size < 2) continue
        val total = parts.sumOf { (w, s) -> w * s } / parts.sumOf { it.first }
        out[day] = RecoveryScore(
            total = total.roundToInt().coerceIn(0, 100),
            sleep = sleep.roundToInt(),
            hrv = hrvPart?.roundToInt(),
            heart = heartPart?.roundToInt(),
            load = loadPart?.roundToInt(),
        )
    }
    return out
}

/** 4 h ou moins → 0, 8 h ou plus → 100 : le haut de l'échelle de couleurs du sommeil. */
internal fun sleepScore(minutes: Double): Double = ((minutes - 240.0) / 240.0 * 100.0).coerceIn(0.0, 100.0)

/** Un jour dans la moyenne vaut 60, deux écarts-types au-dessus 100, trois en dessous 0. */
private fun zScore(z: Double): Double = (60.0 + 20.0 * z).coerceIn(0.0, 100.0)

/** Sans ligne de base, le cœur retombe sur les seuils absolus de sa grille : 0, 25… 100. */
internal fun absoluteHeartScore(bpm: Double): Double = (HEART_BOUNDS.size - HEART_BOUNDS.count { bpm >= it }) * 25.0

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

/** Moyenne et écart-type des [RECOVERY_BASELINE_DAYS] jours qui précèdent, [skip] jours avant [day]. */
private fun baseline(series: Map<LocalDate, Double>, day: LocalDate, skip: Int): Pair<Double, Double>? {
    val values = (skip until skip + RECOVERY_BASELINE_DAYS).mapNotNull { series[day.minusDays(it.toLong())] }
    if (values.size < MIN_BASELINE) return null
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    return mean to sqrt(variance)
}
