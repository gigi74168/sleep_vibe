package com.paul.sleeptrack

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import com.paul.sleeptrack.ui.theme.AubeTokens
import com.paul.sleeptrack.ui.theme.AubeType
import com.paul.sleeptrack.ui.theme.SleepColors
import com.paul.sleeptrack.ui.theme.SleepTheme
import java.time.LocalDate

// Previews d'Android Studio, compilées en debug seulement. Elles tournent sur les données de
// démo, sans Health Connect.

private val demo: HealthData by lazy {
    val year = LocalDate.now().year
    (demoHealthData(year - 1) + demoHealthData(year))
}

/** Le thème, un fond, et un registre de résultats factice pour les écrans qui en lancent. */
@Composable
private fun Frame(colors: SleepColors, content: @Composable () -> Unit) {
    val owner = remember {
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) = Unit
            }
        }
    }
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        SleepTheme(colors) {
            Box(Modifier.background(colors.background).padding(20.dp)) { content() }
        }
    }
}

@Composable
private fun Home(colors: SleepColors) = Frame(colors) {
    HomeScreen(
        recent = demo.since(LocalDate.now().minusDays(RECENT_DAYS)),
        visibleMetrics = Metric.entries,
        goals = Goals(),
        home = HomePrefs(),
        missingHrv = false,
        showNotes = true,
        demo = true,
        onOpenMetric = {},
        onRequestPermissions = {},
        onExitDemo = {},
        onPersonalize = {},
    )
}

@Composable
private fun Grids(colors: SleepColors) = Frame(colors) {
    MainScreen(
        year = LocalDate.now().year,
        metric = Metric.SLEEP,
        visibleMetrics = Metric.entries,
        goals = Goals(),
        display = DisplayPrefs(),
        data = demo.filterYear(LocalDate.now().year),
        missing = emptySet(),
        requested = emptySet(),
        demo = true,
        onYear = {},
        onMetric = {},
        onRequestPermissions = {},
        onExitDemo = {},
    )
}

@Composable
private fun SettingsPreview(colors: SleepColors) = Frame(colors) { SettingsScreen(data = demo) }

@Preview(name = "Accueil · Aube", widthDp = 412, heightDp = 1500)
@Composable
private fun HomeAube() = Home(AubeTokens.Aube)

@Preview(name = "Accueil · Nuit tombée", widthDp = 412, heightDp = 1500)
@Composable
private fun HomeNuit() = Home(AubeTokens.NuitTombee)

@Preview(name = "Grilles · Aube", widthDp = 412, heightDp = 1800)
@Composable
private fun GridsAube() = Grids(AubeTokens.Aube)

@Preview(name = "Grilles · Nuit tombée", widthDp = 412, heightDp = 1800)
@Composable
private fun GridsNuit() = Grids(AubeTokens.NuitTombee)

@Preview(name = "Réglages · Aube", widthDp = 412, heightDp = 1600)
@Composable
private fun SettingsAube() = SettingsPreview(AubeTokens.Aube)

@Preview(name = "Réglages · Nuit tombée", widthDp = 412, heightDp = 1600)
@Composable
private fun SettingsNuit() = SettingsPreview(AubeTokens.NuitTombee)

// ------------------------------------------------------------------ Lever de soleil

@Composable
private fun Gauge(colors: SleepColors, score: Int?) = Frame(colors) {
    SunriseGauge(score = score, threshold = Goals.DEFAULT_RECOVERY_GOOD)
}

@Preview(name = "Jauge 34 · Aube")
@Composable
private fun Gauge34() = Gauge(AubeTokens.Aube, 34)

@Preview(name = "Jauge 73 · Aube")
@Composable
private fun Gauge73() = Gauge(AubeTokens.Aube, 73)

@Preview(name = "Jauge sans score · Aube")
@Composable
private fun GaugeNone() = Gauge(AubeTokens.Aube, null)

@Preview(name = "Jauge 34 · Nuit tombée")
@Composable
private fun Gauge34Nuit() = Gauge(AubeTokens.NuitTombee, 34)

@Preview(name = "Jauge 73 · Nuit tombée")
@Composable
private fun Gauge73Nuit() = Gauge(AubeTokens.NuitTombee, 73)

@Preview(name = "Jauge sans score · Nuit tombée")
@Composable
private fun GaugeNoneNuit() = Gauge(AubeTokens.NuitTombee, null)

// ------------------------------------------------------------------ Pastilles

/** Tous les états d'une pastille, en « Moyenne » (13 dp), « Ajustée » (≈ 5,3 dp) et « Très grande » (24 dp). */
@Composable
private fun PastilleStates(colors: SleepColors) = Frame(colors) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(13.dp, 5.3.dp, 24.dp).forEach { side -> PastilleRow(colors, side) }
    }
}

@Composable
private fun PastilleRow(c: SleepColors, side: Dp) {
    val states = (0..4).map { "Niveau ${it + 1}" to CellContent.Level(it) } +
        listOf("Vide" to CellContent.Empty, "À venir" to CellContent.Future)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        states.forEach { (label, content) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(side + 6.dp)) { drawPastille(center, side.toPx(), content, c) }
                Text(label, color = c.ink2, style = AubeType.axis)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(side + 6.dp)) {
                drawPastille(center, side.toPx(), CellContent.Level(3), c)
                drawTodayMark(center, side.toPx(), c)
            }
            Text("Aujourd'hui", color = c.ink2, style = AubeType.axis)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(side + 6.dp)) {
                drawPastille(center, side.toPx(), CellContent.Level(4), c)
                drawSelectionMark(center, side.toPx(), c)
            }
            Text("Choisie", color = c.ink2, style = AubeType.axis)
        }
    }
}

@Preview(name = "Pastilles · Aube", widthDp = 520)
@Composable
private fun PastillesAube() = PastilleStates(AubeTokens.Aube)

@Preview(name = "Pastilles · Nuit tombée", widthDp = 520)
@Composable
private fun PastillesNuit() = PastilleStates(AubeTokens.NuitTombee)
