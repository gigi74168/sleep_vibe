package com.paul.sleeptrack

import org.junit.Assert.assertEquals
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
        val scores = recoveryScores(mapOf(day to hours(8.0)), emptyMap(), emptyMap(), emptyMap())
        assertTrue(scores.isEmpty())
    }

    @Test
    fun heartWithoutBaselineFallsBackOnAbsoluteThresholds() {
        val score = recoveryScores(
            nights = mapOf(day to hours(8.0)),
            steps = emptyMap(),
            heart = mapOf(day to 50.0),
            hrv = mapOf(day to 60.0),
        ).getValue(day)
        assertEquals(100, score.heart)
        // La VFC n'a pas de seuil absolu : sans ligne de base, elle ne compte pas.
        assertNull(score.hrv)
        assertNull(score.load)
        assertEquals(100, score.total)
    }

    @Test
    fun anOrdinaryMorningScoresSixtyOnHrvAndHeart() {
        val hrv = flat(day, 14, 50.0) + (day to 50.0)
        val heart = flat(day, 14, 55.0) + (day to 55.0)
        val score = recoveryScores(mapOf(day to hours(6.0)), emptyMap(), heart, hrv).getValue(day)
        assertEquals(60, score.hrv)
        assertEquals(60, score.heart)
        assertEquals(50, score.sleep)
        // (0,30·50 + 0,35·60 + 0,20·60) / 0,85
        assertEquals(56, score.total)
    }

    @Test
    fun hrvAboveBaselineRaisesTheScore() {
        val hrv = (1..14).associate { day.minusDays(it.toLong()) to if (it % 2 == 0) 45.0 else 55.0 } +
            (day to 70.0)
        val score = recoveryScores(mapOf(day to hours(8.0)), emptyMap(), emptyMap(), hrv).getValue(day)
        assertEquals(100, score.hrv)
    }

    @Test
    fun aHeavyDayBeforeLowersTheLoadPart() {
        val steps = flat(day.minusDays(1), 20, 8_000L) + (day.minusDays(1) to 16_000L)
        val score = recoveryScores(mapOf(day to hours(8.0)), steps, emptyMap(), emptyMap()).getValue(day)
        assertEquals(40, score.load)
        assertEquals(100, score.sleep)
        // (0,30·100 + 0,15·40) / 0,45
        assertEquals(80, score.total)
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
        val score = recoveryScores(mapOf(day to hours(8.0)), emptyMap(), mapOf(day to 55.0), hrv).getValue(day)
        assertNull(score.hrv)
        assertNotNull(score.heart)
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
        assertTrue(recoveryScores(year.nights, year.steps, year.heart, year.hrv).isEmpty())
    }
}
