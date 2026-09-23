package com.paul.sleeptrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PastilleTest {
    @Test
    fun diameterGrowsWithTheLevel() {
        val side = 100f
        assertEquals(28f, Pastille.radius(0, side), 1e-4f)
        assertEquals(34f, Pastille.radius(1, side), 1e-4f)
        assertEquals(40f, Pastille.radius(2, side), 1e-4f)
        assertEquals(45f, Pastille.radius(3, side), 1e-4f)
        assertEquals(50f, Pastille.radius(4, side), 1e-4f)
    }

    @Test
    fun onlyTheTwoLowestLevelsGetARingAndOnlyFromEightDp() {
        assertTrue(Pastille.hasRing(0, 8f))
        assertTrue(Pastille.hasRing(1, 13f))
        assertFalse(Pastille.hasRing(2, 24f))
        assertFalse(Pastille.hasRing(4, 24f))
        // Grille « Ajustée » : environ 5 dp, la taille code encore le niveau mais sans anneau.
        assertFalse(Pastille.hasRing(0, 5.3f))
    }

    @Test
    fun selectionSitsCloserOnTinyCells() {
        assertEquals(1.5f, Pastille.selectedOffsetDp(13f), 0f)
        assertEquals(1f, Pastille.selectedOffsetDp(5.3f), 0f)
        // Trait centré sur le rayon : demi-cellule + décalage + demi-trait.
        assertEquals(10f + 1.5f + 1f, Pastille.markRadius(20f, 1.5f, 2f), 1e-4f)
    }
}
