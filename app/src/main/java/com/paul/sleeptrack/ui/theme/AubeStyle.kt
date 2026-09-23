package com.paul.sleeptrack.ui.theme

import android.graphics.Paint
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paul.sleeptrack.R

@OptIn(ExperimentalTextApi::class)
object SleepFonts {
    val YoungSerif = FontFamily(Font(R.font.young_serif, FontWeight.Normal))

    // Une seule police variable : chaque graisse en fixe l'axe « wght ».
    val Figtree = FontFamily(
        listOf(400, 500, 600, 700).map { w ->
            Font(
                R.font.figtree,
                FontWeight(w),
                variationSettings = FontVariation.Settings(FontVariation.weight(w)),
            )
        }
    )
}

/** Les rôles de texte d'Aube. En Classique, chaque appel garde sa taille et sa graisse d'avant. */
enum class TextRole { ScreenTitle, Score, Verdict, CardTitle, Value, SectionTitle, Body, Label, Caption, Overline, Axis }

object AubeType {
    // Chiffres à chasse fixe partout où s'affiche une valeur : durées, pas, scores, axes.
    private const val TNUM = "tnum"
    private val serif = SleepFonts.YoungSerif
    private val sans = SleepFonts.Figtree

    val screenTitle = TextStyle(
        fontFamily = serif, fontWeight = FontWeight.W400, fontSize = 34.sp, lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    )
    val score = TextStyle(
        fontFamily = serif, fontWeight = FontWeight.W400, fontSize = 56.sp, lineHeight = 60.sp,
        fontFeatureSettings = TNUM,
    )
    val verdict = TextStyle(fontFamily = serif, fontWeight = FontWeight.W400, fontSize = 26.sp, lineHeight = 32.sp)
    val cardTitle = TextStyle(fontFamily = serif, fontWeight = FontWeight.W400, fontSize = 24.sp, lineHeight = 30.sp)
    val value = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.W400, fontSize = 26.sp, lineHeight = 32.sp,
        fontFeatureSettings = TNUM,
    )
    val sectionTitle = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.W700, fontSize = 16.sp, lineHeight = 22.sp,
        fontFeatureSettings = TNUM,
    )
    val body = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 22.sp,
        fontFeatureSettings = TNUM,
    )
    val label = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.W600, fontSize = 14.sp, lineHeight = 20.sp,
        fontFeatureSettings = TNUM,
    )
    val caption = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp,
        fontFeatureSettings = TNUM,
    )
    val overline = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.W700, fontSize = 11.sp, lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
    )
    val axis = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.W500, fontSize = 10.sp, lineHeight = 12.sp,
        fontFeatureSettings = TNUM,
    )

    fun of(role: TextRole): TextStyle = when (role) {
        TextRole.ScreenTitle -> screenTitle
        TextRole.Score -> score
        TextRole.Verdict -> verdict
        TextRole.CardTitle -> cardTitle
        TextRole.Value -> value
        TextRole.SectionTitle -> sectionTitle
        TextRole.Body -> body
        TextRole.Label -> label
        TextRole.Caption -> caption
        TextRole.Overline -> overline
        TextRole.Axis -> axis
    }

    /** La typographie Material d'Aube : Figtree partout, aux tailles des rôles ci-dessus. */
    val typography: Typography = Typography().let { t ->
        Typography(
            displayLarge = t.displayLarge.copy(fontFamily = serif),
            displayMedium = t.displayMedium.copy(fontFamily = serif),
            displaySmall = t.displaySmall.copy(fontFamily = serif),
            headlineLarge = screenTitle,
            headlineMedium = verdict,
            headlineSmall = cardTitle,
            titleLarge = cardTitle,
            titleMedium = sectionTitle,
            titleSmall = label,
            bodyLarge = body,
            bodyMedium = caption,
            bodySmall = caption,
            labelLarge = label,
            labelMedium = t.labelMedium.copy(fontFamily = sans, fontWeight = FontWeight.W600),
            labelSmall = t.labelSmall.copy(fontFamily = sans, fontWeight = FontWeight.W600),
        )
    }

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(AubeDimens.SmallRadius),
        small = RoundedCornerShape(AubeDimens.SmallRadius),
        medium = RoundedCornerShape(AubeDimens.TileRadius),
        large = RoundedCornerShape(AubeDimens.SectionRadius),
        extraLarge = RoundedCornerShape(AubeDimens.DialogRadius),
    )
}

/**
 * Le style d'un texte selon le thème : le rôle Aube, ou en Classique la taille et la graisse
 * passées ici, fondues dans le style courant exactement comme le faisait `Text(fontSize = …)`.
 */
@Composable
@ReadOnlyComposable
fun sleepText(
    role: TextRole,
    classicSize: TextUnit = TextUnit.Unspecified,
    classicWeight: FontWeight? = null,
): TextStyle {
    val base = LocalTextStyle.current
    return if (LocalSleepColors.current.isAube) {
        base.merge(AubeType.of(role))
    } else {
        base.merge(TextStyle(fontSize = classicSize, fontWeight = classicWeight))
    }
}

/** Une mesure qui change avec le thème : [classic] en Classique, [aube] en Aube. */
@Composable
@ReadOnlyComposable
fun aubeOr(classic: Dp, aube: Dp): Dp = if (LocalSleepColors.current.isAube) aube else classic

object AubeDimens {
    val CardRadius = 28.dp
    val SectionRadius = 26.dp
    val TileRadius = 22.dp
    val SmallRadius = 14.dp
    val WidgetRadius = 28.dp
    val DialogRadius = 32.dp
    val ScreenMargin = 20.dp
    val CardGap = 14.dp
    val SectionGap = 28.dp
    val CardPadding = 22.dp
    val TilePadding = 16.dp
    val MinTouch = 48.dp
}

object Motion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

/**
 * Une carte d'Aube : en clair, pas de filet mais une ombre douce et brune ; en sombre, pas
 * d'ombre mais un filet de 1 dp.
 */
fun Modifier.aubeCard(c: SleepColors, radius: Dp, fill: Color = c.surface): Modifier {
    val shape = RoundedCornerShape(radius)
    val shadow = c.shadow
    return if (shadow != null) {
        softShadow(shadow, radius).background(fill, shape)
    } else {
        background(fill, shape).border(1.dp, c.outline, shape)
    }
}

/**
 * Deux ombres superposées, `0 1 2` à 6 % et `0 10 28` à 9 %. Compose 1.8 n'a pas encore
 * `Modifier.dropShadow` : on passe par `Paint.setShadowLayer`, que le rendu matériel ne
 * dessine sur des formes qu'à partir d'Android 9. Avant, la carte reste simplement sans ombre.
 */
fun Modifier.softShadow(color: Color, radius: Dp): Modifier = drawWithCache {
    val r = radius.toPx()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.TRANSPARENT }
    val near = color.copy(alpha = 0.06f).toArgb()
    val far = color.copy(alpha = 0.09f).toArgb()
    val nearBlur = cssBlurToRadius(2.dp.toPx())
    val farBlur = cssBlurToRadius(28.dp.toPx())
    val nearY = 1.dp.toPx()
    val farY = 10.dp.toPx()
    onDrawBehind {
        val canvas = drawContext.canvas.nativeCanvas
        paint.setShadowLayer(farBlur, 0f, farY, far)
        canvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        paint.setShadowLayer(nearBlur, 0f, nearY, near)
        canvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
    }
}

/** Le flou CSS (deux sigmas) converti en rayon de `setShadowLayer` (sigma = 0,57735 r + 0,5). */
internal fun cssBlurToRadius(blurPx: Float): Float = ((blurPx / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
