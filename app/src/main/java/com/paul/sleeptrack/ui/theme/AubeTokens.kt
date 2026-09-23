package com.paul.sleeptrack.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Le thème choisi dans les réglages. Classique est l'ancien thème sombre, gardé tel quel. */
enum class ThemeChoice(val key: String, val label: String) {
    AUBE("aube", "Aube"),
    CLASSIQUE("classique", "Classique");

    companion object {
        fun of(key: String?): ThemeChoice = entries.firstOrNull { it.key == key } ?: AUBE
    }
}

/** Ne concerne qu'Aube : Clair affiche Aube, Sombre affiche Nuit tombée. */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Système"),
    LIGHT("light", "Clair"),
    DARK("dark", "Sombre");

    companion object {
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Toutes les couleurs de l'app. Les écrans, les grilles, le widget et l'image de partage les
 * lisent ici ; aucune couleur n'est écrite en dur ailleurs.
 */
@Immutable
data class SleepColors(
    val choice: ThemeChoice,
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    /** Tuiles internes, pistes, cases vides du thème Classique. */
    val surface2: Color,
    /** Filet : séparateurs, horizon, bord des cartes en sombre. */
    val outline: Color,
    /** Anneau d'une case sans donnée. */
    val emptyRing: Color,
    val ink: Color,
    val ink2: Color,
    /** Mentions et petites notes. */
    val ink3: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    /** Le soleil de la jauge. */
    val sun: Color,
    val danger: Color,
    val tabBar: Color,
    val scrim: Color,
    /** Les cinq niveaux, du plus loin de l'objectif (0) au plus proche (4). */
    val levels: List<Color>,
    /** Anneaux des niveaux 1 et 2 des pastilles. */
    val rings: List<Color>,
    /** Ombre douce des cartes ; null quand un filet la remplace. */
    val shadow: Color?,
    val switchThumbOn: Color,
    /** Contour de la case choisie. */
    val selection: Color,
    /** Repère d'aujourd'hui dans les grilles. */
    val today: Color,
    /** Texte d'un résultat heureux : export réussi, lien net entre pas et sommeil. */
    val positive: Color,
    /** Texte du bandeau démo. */
    val notice: Color,
) {
    val isAube: Boolean get() = choice == ThemeChoice.AUBE
}

object AubeTokens {
    val Aube = SleepColors(
        choice = ThemeChoice.AUBE,
        dark = false,
        background = Color(0xFFF7F1EA),
        surface = Color(0xFFFFFDF9),
        surface2 = Color(0xFFF1E8DE),
        outline = Color(0xFFE6D9CB),
        emptyRing = Color(0xFFD9CBBB),
        ink = Color(0xFF2E2522),
        ink2 = Color(0xFF6B5F58),
        ink3 = Color(0xFF7A6D65),
        accent = Color(0xFFB4552F),
        onAccent = Color(0xFFFFFFFF),
        accentContainer = Color(0xFFF7DCCD),
        onAccentContainer = Color(0xFF5A2410),
        sun = Color(0xFFE0894F),
        danger = Color(0xFFB3261E),
        tabBar = Color(0xFFFFFDF9),
        scrim = Color(0xFF2E2522).copy(alpha = 0.42f),
        levels = listOf(
            Color(0xFFFFBEBB),
            Color(0xFFEEAE7B),
            Color(0xFFC0A550),
            Color(0xFF5A9A68),
            Color(0xFF34785A),
        ),
        rings = listOf(Color(0xFFD98079), Color(0xFFC98A55)),
        shadow = Color(0xFF785032),
        switchThumbOn = Color(0xFFFFFFFF),
        selection = Color(0xFF2E2522),
        today = Color(0xFFB4552F),
        positive = Color(0xFFB4552F),
        notice = Color(0xFF5A2410),
    )

    val NuitTombee = SleepColors(
        choice = ThemeChoice.AUBE,
        dark = true,
        background = Color(0xFF1C1715),
        surface = Color(0xFF262019),
        surface2 = Color(0xFF30281F),
        outline = Color(0xFF3D332A),
        emptyRing = Color(0xFF4A3F35),
        ink = Color(0xFFF6EDE4),
        ink2 = Color(0xFFBFB1A6),
        ink3 = Color(0xFFA09286),
        accent = Color(0xFFF0A07E),
        onAccent = Color(0xFF2A1409),
        accentContainer = Color(0xFF4A2C20),
        onAccentContainer = Color(0xFFFFDCCB),
        sun = Color(0xFFF0A07E),
        danger = Color(0xFFFFB4A9),
        tabBar = Color(0xFF221C18),
        scrim = Color(0xFF0A0705).copy(alpha = 0.66f),
        levels = listOf(
            Color(0xFF965351),
            Color(0xFFB37646),
            Color(0xFFBFA14C),
            Color(0xFF8ED09C),
            Color(0xFFA3EAC3),
        ),
        rings = listOf(Color(0xFFC7716D), Color(0xFFD39060)),
        shadow = null,
        switchThumbOn = Color(0xFFFFFFFF),
        selection = Color(0xFFF6EDE4),
        today = Color(0xFFF0A07E),
        positive = Color(0xFFF0A07E),
        notice = Color(0xFFFFDCCB),
    )

    /** Les valeurs de l'ancien thème, une à une : Classique doit se dessiner comme avant. */
    val Classique: SleepColors = run {
        val bg = Color(0xFF0D0D0D)
        val card = Color(0xFF171717)
        val empty = Color(0xFF262626)
        val muted = Color(0xFF8A8A8A)
        val levels = listOf(
            Color(0xFFE5484D),
            Color(0xFFF2994A),
            Color(0xFFF2C94C),
            Color(0xFF8BD17C),
            Color(0xFF2ECC71),
        )
        SleepColors(
            choice = ThemeChoice.CLASSIQUE,
            dark = true,
            background = bg,
            surface = card,
            surface2 = empty,
            outline = empty,
            emptyRing = empty,
            ink = Color(0xFFF2F2F2),
            ink2 = muted,
            ink3 = muted.copy(alpha = 0.7f),
            accent = levels.last(),
            onAccent = bg,
            accentContainer = levels.last(),
            onAccentContainer = bg,
            sun = levels.last(),
            danger = levels[0],
            tabBar = card,
            scrim = Color.Black.copy(alpha = 0.32f),
            levels = levels,
            rings = levels.take(2),
            shadow = null,
            switchThumbOn = bg,
            selection = Color.White,
            today = muted,
            positive = levels[3],
            notice = levels[2],
        )
    }

    fun resolve(choice: ThemeChoice, mode: ThemeMode, systemDark: Boolean): SleepColors = when {
        choice == ThemeChoice.CLASSIQUE -> Classique
        mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && systemDark) -> NuitTombee
        else -> Aube
    }
}
