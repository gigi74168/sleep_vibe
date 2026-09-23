package com.paul.sleeptrack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paul.sleeptrack.ui.theme.LocalSleepColors
import com.paul.sleeptrack.ui.theme.Motion
import com.paul.sleeptrack.ui.theme.SleepColors
import com.paul.sleeptrack.ui.theme.TextRole
import com.paul.sleeptrack.ui.theme.sleepText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

val LongDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

private val DAY_LABELS = listOf("Lun", "", "Mer", "", "Ven", "", "Dim")

/**
 * La grille de l'année. Par défaut elle tient dans la largeur ; si [minPitch] demande des
 * cases plus grandes que cet ajustement, la grille déborde et défile horizontalement, la
 * colonne des jours restant en place. [levelAt] : le niveau (0 à 4) d'un jour, ou null.
 */
@Composable
fun YearHeatmap(
    year: Int,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    levelAt: (LocalDate) -> Int?,
    minPitch: Dp = 0.dp,
) {
    val gridStart = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = (ChronoUnit.DAYS.between(gridStart, LocalDate.of(year, 12, 31)) / 7 + 1).toInt()
    val labelWidth = 28.dp
    val monthsHeight = 16.dp
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentLevelAt = rememberUpdatedState(levelAt)
    val scrollState = rememberScrollState()
    val today = LocalDate.now()
    val c = LocalSleepColors.current
    val haptic = LocalHapticFeedback.current
    val bounce = selectionBounce(selected, c)

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
                            Text(label, color = c.ink2, style = sleepText(TextRole.Axis, labelSize), maxLines = 1, softWrap = false)
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
                            color = c.ink2,
                            style = sleepText(TextRole.Axis, labelSize),
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
                        .pointerInput(year, pitch, c.isAube) {
                            // Le toucher se lit sur la cellule carrée entière, pas sur la pastille.
                            detectTapGestures { pos ->
                                val p = pitch.toPx()
                                val col = (pos.x / p).toInt().coerceIn(0, weeks - 1)
                                val row = (pos.y / p).toInt().coerceIn(0, 6)
                                val day = gridStart.plusDays(col * 7L + row)
                                if (c.isAube && day.year == year) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
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
                            if (c.isAube) {
                                val center = Offset(col * p + p / 2, row * p + p / 2)
                                drawAubeCell(center, s, day, today, selected, currentLevelAt.value(day), c, bounce.value)
                                continue
                            }
                            val topLeft = Offset(col * p + inset, row * p + inset)
                            val color = currentLevelAt.value(day)?.let { c.levels[it] } ?: c.surface2
                            drawRoundRect(color, topLeft, Size(s, s), radius)
                            // Repère discret sur aujourd'hui, pour savoir où on en est dans l'année.
                            if (day == today && day != selected) {
                                drawRoundRect(
                                    c.today, topLeft, Size(s, s), radius,
                                    style = Stroke(1.5.dp.toPx()),
                                )
                            }
                            if (day == selected) {
                                drawRoundRect(c.selection, topLeft, Size(s, s), radius, style = Stroke(1.5.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bande des dernières semaines jusqu'à aujourd'hui, à toucher : calquée sur [YearHeatmap] mais
 * sur une hauteur fixe, avec autant de semaines que la largeur en offre.
 */
@Composable
fun RecentHeatmap(
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    levelAt: (LocalDate) -> Int?,
    height: Dp,
    today: LocalDate = LocalDate.now(),
) {
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentLevelAt = rememberUpdatedState(levelAt)
    val currentSelected = rememberUpdatedState(selected)
    val c = LocalSleepColors.current
    val haptic = LocalHapticFeedback.current
    val bounce = selectionBounce(selected, c)
    val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
        val pitch = height / 7
        val weeks = (maxWidth / pitch).toInt().coerceAtLeast(1)
        val gridStart = lastMonday.minusWeeks((weeks - 1).toLong())
        val gridWidth = pitch * weeks
        val cell = pitch * 0.82f

        Canvas(
            Modifier
                .width(gridWidth)
                .height(pitch * 7)
                .pointerInput(weeks, pitch, gridStart, c.isAube) {
                    detectTapGestures { pos ->
                        val p = pitch.toPx()
                        val col = (pos.x / p).toInt().coerceIn(0, weeks - 1)
                        val row = (pos.y / p).toInt().coerceIn(0, 6)
                        val day = gridStart.plusDays(col * 7L + row)
                        if (!day.isAfter(today)) {
                            if (c.isAube) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            currentOnSelect.value(if (day == currentSelected.value) null else day)
                        }
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
                    if (c.isAube) {
                        val center = Offset(col * p + p / 2, row * p + p / 2)
                        drawAubeCell(center, s, day, today, selected, currentLevelAt.value(day), c, bounce.value)
                        continue
                    }
                    if (day.isAfter(today)) continue
                    val topLeft = Offset(col * p + inset, row * p + inset)
                    val color = currentLevelAt.value(day)?.let { c.levels[it] } ?: c.surface2
                    drawRoundRect(color, topLeft, Size(s, s), radius)
                    // Repère discret sur aujourd'hui, pour savoir où on en est.
                    if (day == today && day != selected) {
                        drawRoundRect(
                            c.today, topLeft, Size(s, s), radius,
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                    if (day == selected) {
                        drawRoundRect(c.selection, topLeft, Size(s, s), radius, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
    }
}

/** Une case d'Aube : pastille (ou anneau vide, ou point à venir), puis ses repères. */
private fun DrawScope.drawAubeCell(
    center: Offset,
    side: Float,
    day: LocalDate,
    today: LocalDate,
    selected: LocalDate?,
    level: Int?,
    c: SleepColors,
    bounce: Float,
) {
    val isSelected = day == selected
    val content = when {
        day.isAfter(today) -> CellContent.Future
        level == null -> CellContent.Empty
        else -> CellContent.Level(level)
    }
    val scale = if (isSelected) bounce else 1f
    drawPastille(center, side, content, c, scale)
    if (day == today && !isSelected) drawTodayMark(center, side, c)
    if (isSelected) drawSelectionMark(center, side, c, scale)
}

/** Le rebond d'une pastille qu'on choisit : 0,72 → 1,12 → 1 en 240 ms. Rien en Classique. */
@Composable
private fun selectionBounce(selected: LocalDate?, c: SleepColors): Animatable<Float, *> {
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(selected, c.isAube) {
        if (!c.isAube || selected == null) {
            bounce.snapTo(1f)
            return@LaunchedEffect
        }
        bounce.snapTo(0.72f)
        bounce.animateTo(1f, keyframes {
            durationMillis = 240
            0.72f at 0 using Motion.Standard
            1.12f at 140 using Motion.Standard
        })
    }
    return bounce
}

@Composable
fun Legend(metric: Metric, scale: Scale) {
    val c = LocalSleepColors.current
    if (c.isAube) {
        // Les mêmes pastilles que la grille, le seuil écrit sous chacune.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(metric.label, color = c.ink2, style = sleepText(TextRole.Caption, 12.sp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                scale.labels.forEachIndexed { level, label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(14.dp)) {
                            drawPastille(center, size.minDimension, CellContent.Level(level), c)
                        }
                        Text(label, color = c.ink2, style = sleepText(TextRole.Axis, 10.sp), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(metric.label, color = c.ink2, style = sleepText(TextRole.Caption, 12.sp))
        Spacer(Modifier.weight(1f))
        scale.colors.zip(scale.labels).forEach { (color, label) ->
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(3.dp))
            Text(label, color = c.ink2, style = sleepText(TextRole.Axis, 10.sp), maxLines = 1, softWrap = false)
            Spacer(Modifier.width(6.dp))
        }
    }
}
