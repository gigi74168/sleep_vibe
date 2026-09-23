package com.paul.sleeptrack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paul.sleeptrack.ui.theme.AubeType
import com.paul.sleeptrack.ui.theme.LocalSleepColors
import com.paul.sleeptrack.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Un mot par niveau de récupération, du plus bas au plus haut. */
internal val VERDICTS = listOf("Au ralenti", "Fatigué", "Correct", "Bien récupéré", "En pleine forme")

/** Niveau (0 à 4) d'un score, avec les seuils qui découlent de l'objectif « au vert ». */
internal fun recoveryLevel(score: Int, threshold: Int): Int =
    Scale(boundsFor(Metric.RECOVERY, Goals(recoveryGood = threshold)), false, emptyList(), emptyList())
        .levelOf(score.toDouble())

// La géométrie de la maquette, en dp : zone 248 × 138, horizon à y = 126, arc de rayon 100.
private const val W = 248f
private const val H = 138f
private const val CX = 124f
private const val CY = 126f
private const val R = 100f

/**
 * La jauge d'Aube : un lever de soleil. L'arc monte de l'horizon gauche jusqu'au score, le
 * soleil posé à son bout ; un petit trait marque le seuil « au vert ». Sans score, le soleil
 * attend sur l'horizon, à demi effacé. [animate] fait monter l'arc et compter le chiffre.
 */
@Composable
fun SunriseGauge(
    score: Int?,
    threshold: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = false,
) {
    val c = LocalSleepColors.current
    val target = (score ?: 0).coerceIn(0, 100).toFloat()
    val progress = remember { Animatable(if (animate) 0f else target) }
    LaunchedEffect(target, animate) {
        if (animate) {
            progress.animateTo(target, tween(1_100, easing = Motion.EmphasizedDecelerate))
        } else {
            progress.snapTo(target)
        }
    }
    val levelColor = score?.let { c.levels[recoveryLevel(it, threshold)] }
    val description = if (score == null) {
        "Score de récupération : pas encore de score"
    } else {
        "Score de récupération : $score sur 100, ${VERDICTS[recoveryLevel(score, threshold)]}"
    }

    Box(
        modifier
            .size(W.dp, H.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = 1.dp.toPx()
            val center = Offset(CX * u, CY * u)
            fun onArc(fraction: Float, radius: Float): Offset {
                val angle = PI + PI * fraction
                return Offset(center.x + radius * u * cos(angle).toFloat(), center.y + radius * u * sin(angle).toFloat())
            }

            drawLine(c.outline, Offset(4 * u, CY * u), Offset((W - 4) * u, CY * u), strokeWidth = 1.5f * u)

            val stroke = Stroke(12 * u, cap = StrokeCap.Round)
            val topLeft = Offset((CX - R) * u, (CY - R) * u)
            val arcSize = Size(2 * R * u, 2 * R * u)
            drawArc(c.surface2, 180f, 180f, false, topLeft, arcSize, style = stroke)
            val p = progress.value / 100f
            if (levelColor != null && p > 0f) {
                drawArc(levelColor, 180f, 180f * p, false, topLeft, arcSize, style = stroke)
            }

            // Le seuil « au vert » : un trait radial juste au-dehors de l'arc.
            val t = threshold.coerceIn(0, 100) / 100f
            drawLine(c.ink3, onArc(t, 108f), onArc(t, 117f), strokeWidth = 1.5f * u)

            val sun = onArc(p, R)
            val alpha = if (score == null) 0.45f else 1f
            drawCircle(c.sun.copy(alpha = 0.25f * alpha), 17 * u, sun)
            drawCircle(c.sun.copy(alpha = alpha), 9 * u, sun)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = (H - CY + 6).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                if (score == null) "—" else "${progress.value.roundToInt()}",
                color = c.ink,
                style = AubeType.score,
            )
            Text("sur 100", color = c.ink2, style = AubeType.caption.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
    }
}
