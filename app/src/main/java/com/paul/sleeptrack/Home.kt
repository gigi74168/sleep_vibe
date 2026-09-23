package com.paul.sleeptrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private val VERDICTS = listOf("Au ralenti", "Fatigué", "Correct", "Bien récupéré", "En pleine forme")

/** L'accueil : la récupération du matin, puis les dernières semaines des métriques choisies. */
@Composable
internal fun HomeScreen(
    recent: HealthData,
    visibleMetrics: List<Metric>,
    goals: Goals,
    home: HomePrefs,
    missingHrv: Boolean,
    showNotes: Boolean,
    demo: Boolean,
    onOpenMetric: (Metric) -> Unit,
    onRequestPermissions: () -> Unit,
    onExitDemo: () -> Unit,
    onPersonalize: () -> Unit,
) {
    val today = LocalDate.now()
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val strips = Metric.entries.filter { it in home.strips && it in visibleMetrics }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Aujourd'hui", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onPersonalize) { Text("Personnaliser", color = Palette.muted, fontSize = 13.sp) }
        }
        Text(today.format(Palette.longDate), color = Palette.muted, fontSize = 14.sp)

        if (demo) DemoBanner(onExitDemo)

        if (home.card) {
            Panel {
                RecoveryCard(recent, today, goals, home.gauge, home.breakdown, missingHrv, showNotes, onRequestPermissions)
            }
        }

        strips.forEach { metric ->
            HomeStrip(
                metric = metric,
                data = recent,
                goals = goals,
                today = today,
                height = Prefs.STRIP_SIZES[home.stripSize].dp,
                selected = selected,
                onSelect = { day -> selected = day },
                onOpenYear = { onOpenMetric(metric) },
            )
        }

        if (strips.isEmpty() && showNotes) {
            Text(
                "Aucune bande à afficher : choisis-en dans les réglages de l'accueil.",
                color = Palette.muted,
                fontSize = 13.sp,
            )
        }

        if (home.tapDetail && selected != null && strips.isNotEmpty()) {
            DayDetail(selected!!, recent, visibleMetrics, goals)
        }

        if (showNotes && strips.isNotEmpty()) {
            Text(
                "Touche une case pour voir son détail, « Année › » pour ouvrir sa grille.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun HomeStrip(
    metric: Metric,
    data: HealthData,
    goals: Goals,
    today: LocalDate,
    height: Dp,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    onOpenYear: () -> Unit,
) {
    val series = remember(metric, data) { data.series(metric) }
    val scale = remember(metric, goals) { scaleFor(metric, goals) }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(metric.label, color = Palette.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (series.isNotEmpty()) {
                    Text("moy. ${metric.format(series.values.average())}", color = Palette.muted, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = onOpenYear, contentPadding = PaddingValues(0.dp)) {
                    Text("Année ›", color = Palette.muted, fontSize = 12.sp)
                }
            }
            RecentHeatmap(
                selected = selected,
                onSelect = onSelect,
                colorAt = { day -> series[day]?.let(scale::colorOf) },
                height = height,
                today = today,
            )
        }
    }
}

@Composable
private fun RecoveryCard(
    recent: HealthData,
    today: LocalDate,
    goals: Goals,
    showGauge: Boolean,
    showBreakdown: Boolean,
    missingHrv: Boolean,
    showNotes: Boolean,
    onRequestPermissions: () -> Unit,
) {
    // La nuit dernière n'est pas toujours déjà synchronisée : on se rabat sur celle d'avant.
    val latest = listOf(today, today.minusDays(1))
        .firstNotNullOfOrNull { day -> recent.recovery[day]?.let { day to it } }
    val scale = remember(goals) { scaleFor(Metric.RECOVERY, goals) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Récupération", color = Palette.text, fontWeight = FontWeight.SemiBold)

        if (recent.recoveryConfig.components.isEmpty()) {
            Text(
                "Aucune composante active : choisis-en au moins une dans les réglages du score " +
                    "de récupération.",
                color = Palette.muted,
                fontSize = 13.sp,
            )
        } else if (latest == null) {
            Text(
                "Pas encore de score. Il faut la nuit dernière et au moins une autre composante " +
                    "active, comparée à ta propre moyenne, qui demande une semaine de relevés.",
                color = Palette.muted,
                fontSize = 13.sp,
            )
        } else {
            val (day, score) = latest
            val color = scale.colorOf(score.total.toDouble())
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showGauge) {
                    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = 10.dp.toPx()
                            val inset = stroke / 2
                            val arcSize = Size(size.width - stroke, size.height - stroke)
                            val topLeft = Offset(inset, inset)
                            // Un arc de 270°, ouvert en bas, comme une jauge.
                            drawArc(Palette.empty, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                            drawArc(
                                color, 135f, 270f * score.total / 100f, false, topLeft, arcSize,
                                style = Stroke(stroke, cap = StrokeCap.Round),
                            )
                        }
                        Text("${score.total}", color = color, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(16.dp))
                } else {
                    Text("${score.total}", color = color, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(16.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showGauge) {
                        Text(
                            VERDICTS[scale.levelOf(score.total.toDouble())],
                            color = color,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        if (day == today) "Ce matin, sur 100" else "Hier matin : la nuit dernière n'est pas encore là",
                        color = Palette.muted,
                        fontSize = 13.sp,
                    )
                }
            }
            if (showBreakdown) RecoveryBreakdown(day, score, recent, goals)
        }

        if (missingHrv) {
            Text(
                "Sans la variabilité cardiaque, le score repose sur les autres composantes actives : " +
                    "autorise-la dans Health Connect pour le compléter.",
                color = Palette.muted,
                fontSize = 13.sp,
            )
            OutlinedButton(onClick = onRequestPermissions) {
                Text("Autoriser la VFC", color = Palette.text)
            }
        } else if (showNotes && recent.hrv.isEmpty() && RecoveryComponent.HRV in recent.recoveryConfig.components) {
            Text(
                "Aucune VFC trouvée dans Health Connect : ta montre n'en enregistre peut-être " +
                    "pas. Le score se calcule sans elle.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
        if (showNotes) {
            Text(
                "Chaque composante se compare à tes semaines précédentes ; une grosse journée de " +
                    "pas la veille fait baisser le score. Indicatif, pas médical.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

/** Les composantes actives d'un score : la valeur mesurée, puis ce qu'elle rapporte sur 100. */
@Composable
internal fun RecoveryBreakdown(day: LocalDate, score: RecoveryScore, data: HealthData, goals: Goals) {
    val scale = scaleFor(Metric.RECOVERY, goals)
    val active = data.recoveryConfig.components
    val yesterdaySteps = data.steps[day.minusDays(1)]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (RecoveryComponent.SLEEP in active) {
            ComponentLine("Sommeil", data.nights[day]?.let(::formatDuration), score.sleep, scale)
        }
        if (RecoveryComponent.HRV in active) {
            ComponentLine("VFC", data.hrv[day]?.let { "%.0f ms".format(it) }, score.hrv, scale)
        }
        if (RecoveryComponent.LOAD in active) {
            ComponentLine("Pas de la veille", yesterdaySteps?.let(::formatSteps), score.load, scale)
        }
    }
}

@Composable
private fun ComponentLine(label: String, measured: String?, points: Int?, scale: Scale) {
    // Une valeur sans points : mesurée, mais la ligne de base n'a pas encore assez de jours.
    val detail = when {
        measured == null -> "Aucune donnée"
        points == null -> "$measured · moyenne en cours"
        else -> "$measured · $points"
    }
    DetailLine(label, detail, points?.let { scale.colorOf(it.toDouble()) })
}
