package com.paul.sleeptrack.ui.theme

import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

val LocalSleepColors = staticCompositionLocalOf { AubeTokens.Classique }

@Composable
fun SleepTheme(colors: SleepColors, content: @Composable () -> Unit) {
    val scheme = remember(colors) { colorSchemeOf(colors) }
    SystemBars(colors)
    CompositionLocalProvider(LocalSleepColors provides colors) {
        if (colors.isAube) {
            MaterialTheme(colorScheme = scheme, typography = AubeType.typography, shapes = AubeType.shapes, content = content)
        } else {
            // Classique : la typographie et les formes par défaut, comme avant.
            MaterialTheme(colorScheme = scheme, content = content)
        }
    }
}

private fun colorSchemeOf(c: SleepColors): ColorScheme {
    // Classique : exactement le schéma d'avant, le reste vient des valeurs par défaut de M3.
    if (!c.isAube) return darkColorScheme(background = c.background, surface = c.surface)
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentContainer,
        onPrimaryContainer = c.onAccentContainer,
        secondary = c.accent,
        onSecondary = c.onAccent,
        secondaryContainer = c.accentContainer,
        onSecondaryContainer = c.onAccentContainer,
        tertiary = c.sun,
        background = c.background,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        surfaceVariant = c.surface2,
        onSurfaceVariant = c.ink2,
        // Pas de teinte d'élévation : les menus gardent la couleur des cartes.
        surfaceTint = Color.Transparent,
        inverseSurface = c.ink,
        inverseOnSurface = c.surface,
        outline = c.ink3,
        outlineVariant = c.outline,
        error = c.danger,
        onError = c.onAccent,
        scrim = c.scrim,
        surfaceBright = c.surface,
        surfaceDim = c.background,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface2,
        surfaceContainerHighest = c.surface2,
    )
}

/** Icônes des barres système lisibles sur le fond du thème, et fenêtre de la même couleur. */
@Composable
private fun SystemBars(c: SleepColors) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    LaunchedEffect(c) {
        if (c.isAube) {
            val transparent = Color.Transparent.toArgb()
            val style = if (c.dark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            }
            activity.enableEdgeToEdge(style, style)
        } else {
            activity.enableEdgeToEdge()
        }
        activity.window.setBackgroundDrawable(ColorDrawable(c.background.toArgb()))
    }
}
