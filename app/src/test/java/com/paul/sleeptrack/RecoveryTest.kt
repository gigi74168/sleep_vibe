package com.paul.sleeptrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class RecoveryTest {
    private val day = LocalDate.of(2026, 3, 10)

    /** Les [n] jours qui précèdent [before], à la même valeur. */
    private fun <T> flat(before: LocalDate, n: Int, value: T) =
        (1..n).associate { before.minusDays(it.toLong()) to value }

    private fun hours(h: Double) = Duration.ofMinutes((h * 60).toLong())

    @Test
    fun sleepScoreGoesFromFourToEightHours() {
        assertEquals(0.0, sleepScore(240.0), 1e-9)
        assertEquals(50.0, sleepScore(360.0), 1e-9)
        assertEquals(100.0, sleepScore(480.0), 1e-9)
        assertEquals(100.0, sleepScore(600.0), 1e-9)
        assertEquals(0.0, sleepScore(120.0), 1e-9)
    }

    @Test
    fun aNightAloneGivesNoScore() {
        val scores = recoveryScores(mapOf(day to hours(8.0)), emptyMap(), emptyMap())
        assertTrue(scores.isEmpty())
    }

    @Test
    fun sleepAndHrvAverageToFiftyFive() {
        val hrv = flat(day, 14, 50.0) + (day to 50.0)
        val score = recoveryScores(mapOf(day to hours(6.0)), emptyMap(), hrv).getValue(day)
        assertEquals(60, score.hrv)
        assertEquals(50, score.sleep)
        assertNull(score.load)
        // Moyenne simple, plus de pondération : (50 + 60) / 2.
        assertEquals(55, score.total)
    }

    @Test
    fun hrvAboveBaselineRaisesTheScore() {
        val hrv = (1..14).associate { day.minusDays(it.toLong()) to if (it % 2 == 0) 45.0 else 55.0 } +
            (day to 70.0)
        val score = recoveryScores(mapOf(day to hours(8.0)), emptyMap(), hrv).getValue(day)
        assertEquals(100, score.hrv)
    }

    @Test
    fun aHeavyDayBeforeLowersTheLoadPart() {
        val steps = flat(day.minusDays(1), 20, 8_000L) + (day.minusDays(1) to 16_000L)
        val score = recoveryScores(mapOf(day to hours(8.0)), steps, emptyMap()).getValue(day)
        assertEquals(40, score.load)
        assertEquals(100, score.sleep)
        assertNull(score.hrv)
        // Moyenne simple : (100 + 40) / 2.
        assertEquals(70, score.total)
    }

    @Test
    fun loadScoreIsFreeUpToTwentyPercentAboveUsual() {
        assertEquals(100.0, loadScore(9_600.0, 8_000.0), 1e-9)
        assertEquals(70.0, loadScore(12_800.0, 8_000.0), 1e-9)
        assertEquals(40.0, loadScore(30_000.0, 8_000.0), 1e-9)
        assertEquals(100.0, loadScore(5_000.0, 0.0), 1e-9)
    }

    @Test
    fun baselineNeedsSevenDays() {
        val hrv = flat(day, 6, 50.0) + (day to 50.0)
        val steps = flat(day.minusDays(1), 20, 8_000L) + (day.minusDays(1) to 8_000L)
        val score = recoveryScores(mapOf(day to hours(8.0)), steps, hrv).getValue(day)
        assertNull(score.hrv)
        assertNotNull(score.load)
    }

    @Test
    fun aDisabledComponentIsExcluded() {
        val hrv = flat(day, 14, 50.0) + (day to 70.0)
        val steps = flat(day.minusDays(1), 20, 8_000L) + (day.minusDays(1) to 8_000L)
        val config = RecoveryConfig(components = setOf(RecoveryComponent.SLEEP, RecoveryComponent.LOAD))
        val score = recoveryScores(mapOf(day to hours(8.0)), steps, hrv, config).getValue(day)
        assertNull(score.hrv)
        assertEquals(100, score.sleep)
        assertNotNull(score.load)
    }

    @Test
    fun sleepDisabledScoresWithoutANight() {
        val hrv = flat(day, 14, 50.0) + (day to 70.0)
        val steps = flat(day.minusDays(1), 20, 8_000L) + (day.minusDays(1) to 8_000L)
        val config = RecoveryConfig(components = setOf(RecoveryComponent.HRV, RecoveryComponent.LOAD))
        val scores = recoveryScores(emptyMap(), steps, hrv, config)
        assertNotNull(scores[day])
        assertNull(scores.getValue(day).sleep)
    }

    @Test
    fun zeroComponentsGivesNoScores() {
        val config = RecoveryConfig(components = emptySet())
        val scores = recoveryScores(mapOf(day to hours(8.0)), emptyMap(), emptyMap(), config)
        assertTrue(scores.isEmpty())
    }

    @Test
    fun baselineWindowFollowsTheConfiguredPeriod() {
        val hrv = (1..30).associate { day.minusDays(it.toLong()) to if (it <= 14) 80.0 else 20.0 } + (day to 50.0)
        val score14 = recoveryScores(
            mapOf(day to hours(8.0)), emptyMap(), hrv, RecoveryConfig(baselineDays = 14),
        ).getValue(day)
        val score28 = recoveryScores(
            mapOf(day to hours(8.0)), emptyMap(), hrv, RecoveryConfig(baselineDays = 28),
        ).getValue(day)
        assertNotEquals(score14.hrv, score28.hrv)
    }

    @Test
    fun januaryKeepsItsDecemberBaselineAfterFilteringTheYear() {
        val jan1 = LocalDate.of(2026, 1, 1)
        val data = HealthData(
            nights = mapOf(jan1 to hours(7.0)),
            hrv = flat(jan1, 20, 50.0) + (jan1 to 50.0),
        )
        val year = data.filterYear(2026)
        assertEquals(setOf(jan1), year.hrv.keys)
        assertEquals(60, year.recovery.getValue(jan1).hrv)
        // Recalculé sur l'année seule, le score perdrait sa VFC faute de ligne de base.
        assertTrue(recoveryScores(year.nights, year.steps, year.hrv).isEmpty())
    }

    @Test
    fun boundsForDefaultGoalsMatchOldThresholds() {
        val goals = Goals()
        assertEquals(listOf(300.0, 360.0, 420.0, 480.0), boundsFor(Metric.SLEEP, goals))
        assertEquals(listOf(3000.0, 6000.0, 8000.0, 10000.0), boundsFor(Metric.STEPS, goals))
        assertEquals(listOf(120.0, 180.0, 240.0, 300.0), boundsFor(Metric.SCREEN, goals))
        assertEquals(listOf(34.0, 50.0, 67.0, 80.0), boundsFor(Metric.RECOVERY, goals))
    }

    @Test
    fun boundsForFollowsModifiedGoals() {
        val goals = Goals(sleepMinutes = 480, steps = 12_000, screenMinutes = 120, recoveryGood = 70)
        assertEquals(listOf(360.0, 420.0, 480.0, 540.0), boundsFor(Metric.SLEEP, goals))
        assertEquals(listOf(3600.0, 7200.0, 9600.0, 12000.0), boundsFor(Metric.STEPS, goals))
        assertEquals(listOf(80.0, 120.0, 160.0, 200.0), boundsFor(Metric.SCREEN, goals))
        assertEquals(listOf(37.0, 53.0, 70.0, 83.0), boundsFor(Metric.RECOVERY, goals))
    }
}
