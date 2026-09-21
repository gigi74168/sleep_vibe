package com.paul.sleeptrack

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object Palette {
    val bg = Color(0xFF0D0D0D)
    val card = Color(0xFF171717)
    val empty = Color(0xFF262626)
    val text = Color(0xFFF2F2F2)
    val muted = Color(0xFF8A8A8A)

    // Du pire au meilleur : rouge, orange, jaune, vert clair, vert vif.
    val levels = listOf(
        Color(0xFFE5484D),
        Color(0xFFF2994A),
        Color(0xFFF2C94C),
        Color(0xFF8BD17C),
        Color(0xFF2ECC71),
    )

    // Le poids n'a pas de « bon » côté : dégradé neutre, du plus léger au plus lourd.
    val weightRamp = listOf(
        Color(0xFFA8E4F2),
        Color(0xFF6FC3E0),
        Color(0xFF4A95C7),
        Color(0xFF3A6BA8),
        Color(0xFF2E4685),
    )

    val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
}

private val DAY_LABELS = listOf("Lun", "", "Mer", "", "Ven", "", "Dim")

/**
 * La grille de l'année. Par défaut elle tient dans la largeur ; si [minPitch] demande des
 * cases plus grandes que cet ajustement, la grille déborde et défile horizontalement, la
 * colonne des jours restant en place.
 */
@Composable
fun YearHeatmap(
    year: Int,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    colorAt: (LocalDate) -> Color?,
    minPitch: Dp = 0.dp,
) {
    val gridStart = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = (ChronoUnit.DAYS.between(gridStart, LocalDate.of(year, 12, 31)) / 7 + 1).toInt()
    val labelWidth = 28.dp
    val monthsHeight = 16.dp
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentColorAt = rememberUpdatedState(colorAt)
    val scrollState = rememberScrollState()
    val today = LocalDate.now()

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fitPitch = (maxWidth - labelWidth) / weeks
        val pitch = maxOf(fitPitch, minPitch)
        val gridWidth = pitch * weeks
        val cell = pitch * 0.8f
        val scrolls = pitch > fitPitch
        val labelSize = if (pitch >= 16.dp) 11.sp else 9.sp

        Row {
            Column(Modifier.width(labelWidth)) {
                Spacer(Modifier.height(monthsHeight))
                DAY_LABELS.forEach { label ->
                    Box(Modifier.height(pitch), contentAlignment = Alignment.CenterStart) {
                        if (label.isNotEmpty()) {
                            Text(label, color = Palette.muted, fontSize = labelSize, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            Column(
                if (scrolls) Modifier.horizontalScroll(scrollState) else Modifier
            ) {
                Box(Modifier.width(gridWidth).height(monthsHeight)) {
                    for (month in 1..12) {
                        val first = LocalDate.of(year, month, 1)
                        val col = (ChronoUnit.DAYS.between(gridStart, first) / 7).toInt()
                        Text(
                            first.month.getDisplayName(TextStyle.SHORT, Locale.FRENCH)
                                .take(3)
                                .replaceFirstChar { it.uppercase() },
                            color = Palette.muted,
                            fontSize = labelSize,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.offset(x = pitch * col),
                        )
                    }
                }
                Canvas(
                    Modifier
                        .width(gridWidth)
                        .height(pitch * 7)
                        .pointerInput(year, pitch) {
                            detectTapGestures { pos ->
                                val p = pitch.toPx()
                                val col = (pos.x / p).toInt().coerceIn(0, weeks - 1)
                                val row = (pos.y / p).toInt().coerceIn(0, 6)
                                val day = gridStart.plusDays(col * 7L + row)
                                currentOnSelect.value(day.takeIf { it.year == year })
                            }
                        }
                ) {
                    val p = pitch.toPx()
                    val s = cell.toPx()
                    val inset = (p - s) / 2
                    val radius = CornerRadius(s * 0.3f)
                    for (col in 0 until weeks) {
                        for (row in 0..6) {
                            val day = gridStart.plusDays(col * 7L + row)
                            if (day.year != year) continue
                            val topLeft = Offset(col * p + inset, row * p + inset)
                            drawRoundRect(currentColorAt.value(day) ?: Palette.empty, topLeft, Size(s, s), radius)
                            // Repère discret sur aujourd'hui, pour savoir où on en est dans l'année.
                            if (day == today && day != selected) {
                                drawRoundRect(
                                    Palette.muted, topLeft, Size(s, s), radius,
                                    style = Stroke(1.5.dp.toPx()),
                                )
                            }
                            if (day == selected) {
                                drawRoundRect(Color.White, topLeft, Size(s, s), radius, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Legend(metric: Metric, scale: Scale) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(metric.label, color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        scale.colors.zip(scale.labels).forEach { (color, label) ->
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(3.dp))
            Text(label, color = Palette.muted, fontSize = 10.sp, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(6.dp))
        }
    }
}
