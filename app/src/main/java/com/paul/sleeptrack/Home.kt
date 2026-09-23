package com.paul.sleeptrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Ce que l'accueil montre en bandes, dans cet ordre, si la métrique est visible. */
private val HOME_STRIPS = listOf(Metric.SLEEP, Metric.STEPS, Metric.RECOVERY)

private val VERDICTS = listOf("Au ralenti", "Fatigué", "Correct", "Bien récupéré", "En pleine forme")

/** L'accueil : la récupération du matin, puis les dernières semaines de sommeil et de pas. */
@Composable
internal fun HomeScreen(
    recent: HealthData,
    visibleMetrics: List<Metric>,
    missingHrv: Boolean,
    showNotes: Boolean,
    demo: Boolean,
    onOpenMetric: (Metric) -> Unit,
    onRequestPermissions: () -> Unit,
    onExitDemo: () -> Unit,
) {
    val today = LocalDate.now()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Aujourd'hui", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(today.format(Palette.longDate), color = Palette.muted, fontSize = 14.sp)
        }

        if (demo) DemoBanner(onExitDemo)

        Panel { RecoveryCard(recent, today, missingHrv, showNotes, onRequestPermissions) }

        HOME_STRIPS.filter { it in visibleMetrics }.forEach { metric ->
            RecentStrip(metric, recent, today) { onOpenMetric(metric) }
        }
        if (showNotes) {
            Text(
                "Touche une bande pour ouvrir sa grille de l'année.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun RecoveryCard(
    recent: HealthData,
    today: LocalDate,
    missingHrv: Boolean,
    showNotes: Boolean,
    onRequestPermissions: () -> Unit,
) {
    // La nuit dernière n'est pas toujours déjà synchronisée : on se rabat sur celle d'avant.
    val latest = listOf(today, today.minusDays(1))
        .firstNotNullOfOrNull { day -> recent.recovery[day]?.let { day to it } }
    val scale = remember(recent) { scaleFor(Metric.RECOVERY, recent) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Récupération", color = Palette.text, fontWeight = FontWeight.SemiBold)

        if (latest == null) {
            Text(
                "Pas encore de score. Il faut la nuit dernière et au moins une autre donnée : " +
                    "VFC, cœur au repos ou pas de la veille, comparés à ta propre moyenne, qui " +
                    "demande une semaine de relevés.",
                color = Palette.muted,
                fontSize = 13.sp,
            )
        } else {
            val (day, score) = latest
            val color = scale.colorOf(score.total.toDouble())
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        VERDICTS[scale.levelOf(score.total.toDouble())],
                        color = color,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (day == today) "Ce matin, sur 100" else "Hier matin : la nuit dernière n'est pas encore là",
                        color = Palette.muted,
                        fontSize = 13.sp,
                    )
                }
            }
            RecoveryBreakdown(day, score, recent)
        }

        if (missingHrv) {
            Text(
                "Sans la variabilité cardiaque, le score repose sur le sommeil, le cœur au repos " +
                    "et les pas : autorise-la dans Health Connect pour le compléter.",
                color = Palette.muted,
                fontSize = 13.sp,
            )
            OutlinedButton(onClick = onRequestPermissions) {
                Text("Autoriser la VFC", color = Palette.text)
            }
        } else if (showNotes && recent.hrv.isEmpty()) {
            Text(
                "Aucune VFC trouvée dans Health Connect : ta montre n'en enregistre peut-être " +
                    "pas. Le score se calcule sans elle.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
        if (showNotes) {
            Text(
                "VFC et cœur au repos se comparent à tes quatre dernières semaines ; une grosse " +
                    "journée de pas la veille fait baisser le score. Indicatif, pas médical.",
                color = Palette.muted.copy(alpha = 0.7f),
                fontSize = 11.sp,
            )
        }
    }
}

/** Les quatre composantes d'un score : la valeur mesurée, puis ce qu'elle rapporte sur 100. */
@Composable
internal fun RecoveryBreakdown(day: LocalDate, score: RecoveryScore, data: HealthData) {
    val scale = scaleFor(Metric.RECOVERY, data)
    val yesterdaySteps = data.steps[day.minusDays(1)]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ComponentLine(
            "Sommeil",
            data.nights[day]?.let(::formatDuration),
            score.sleep,
            scale,
        )
        ComponentLine("VFC", data.hrv[day]?.let { "%.0f ms".format(it) }, score.hrv, scale)
        ComponentLine("Cœur au repos", data.heart[day]?.let { "%.0f bpm".format(it) }, score.heart, scale)
        ComponentLine("Pas de la veille", yesterdaySteps?.let(::formatSteps), score.load, scale)
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

/** La bande du widget, sur une carte ; la toucher ouvre la grille de la métrique. */
@Composable
private fun RecentStrip(metric: Metric, data: HealthData, today: LocalDate, onClick: () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        val height = 140.dp
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { height.roundToPx() }
        val image = remember(metric, data, today, widthPx, heightPx) {
            renderStrip(metric, data, widthPx, heightPx, today, background = Palette.card.toArgb()).asImageBitmap()
        }
        Image(
            bitmap = image,
            contentDescription = "${metric.detailLabel}, dernières semaines",
            modifier = Modifier.fillMaxWidth().height(height),
        )
    }
}
