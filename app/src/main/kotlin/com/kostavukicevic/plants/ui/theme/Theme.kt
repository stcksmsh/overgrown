// SPDX-License-Identifier: GPL-3.0-only
package com.kostavukicevic.plants.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostavukicevic.plants.R

// Every design token from docs/DESIGN.md sections 2-4 lives in this file, each value once.
// Components take the roles below, never raw values.

// ---- Colour (DESIGN.md section 2) ------------------------------------------------------

// Values shared by several roles are named once here so each hex appears a single time.
private val InkLight = Color(0xFF1B1B1B)
private val White = Color(0xFFFFFFFF)
private val GroundDark = Color(0xFF121512)
private val MossGreen = Color(0xFF2F6B3F)
private val BrickRed = Color(0xFFB8401C)
private val BrickTint = Color(0xFFF3D9CF)

/** The colour roles that differ between light and dark. */
@Immutable
data class OvergrownColors(
    val ground: Color,
    val surface: Color,
    val ink: Color,
    val onInk: Color,
    val muted: Color,
    val outline: Color,
    /** PotGauge fill on neutral surfaces. */
    val gaugeFill: Color,
    /** Error text on ground and surface for the existing screens; see docs/DECISIONS.md D32. */
    val errorText: Color,
)

val LightColors = OvergrownColors(
    ground = Color(0xFFEEF0EC),
    surface = White,
    ink = InkLight,
    onInk = White,
    muted = Color(0xFF4D4D4D),
    outline = Color(0xFFC9CDC6),
    gaugeFill = MossGreen,
    errorText = BrickRed,
)

val DarkColors = OvergrownColors(
    ground = GroundDark,
    surface = Color(0xFF1E221E),
    ink = Color(0xFFF2F4F0),
    onInk = GroundDark,
    muted = Color(0xFFB4BAB2),
    outline = Color(0xFF3A403A),
    gaugeFill = Color(0xFF7FC08F),
    errorText = BrickTint,
)

/** State blocks, the same in both themes. Each carries its own text colour. */
enum class StateBlock(val fill: Color, val text: Color) {
    /** Needs water. */
    Sky(Color(0xFFBFD9F2), InkLight),

    /** The app has a question. */
    Marigold(Color(0xFFF2B632), InkLight),

    /** Weather alert, destructive confirmation. */
    Brick(BrickRed, White),

    /** Fine or resting; brand colour; selected state. */
    Moss(MossGreen, White),
}

/**
 * A secondary button on any state block: white with dark ink in both themes, as in the Today mockup. DESIGN.md
 * section 5 says surface fill, which is dark in the dark theme; the owner chose the mockup (D41).
 */
object BlockButton {
    val fill = White
    val text = InkLight
}

/** Chip tints on surface, the same in both themes. */
object Tints {
    val moss = Color(0xFFDDE8D8)
    val brick = BrickTint
}

/** The empty part of a PotGauge on a state block with dark text (sky, marigold). */
val GaugeEmptyOnBlock = White

// ---- Type (DESIGN.md section 3) --------------------------------------------------------

val Bricolage = FontFamily(
    Font(R.font.bricolage_grotesque_regular, FontWeight.Normal),
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_extrabold, FontWeight.ExtraBold),
)

/** The six type styles. Sizes are sp, so they follow the system font scale. */
object Type {
    val display = TextStyle(fontFamily = Bricolage, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em)
    val title = TextStyle(fontFamily = Bricolage, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em)
    val cardTitle = TextStyle(fontFamily = Bricolage, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    val body = TextStyle(fontFamily = Bricolage, fontSize = 17.sp, fontWeight = FontWeight.Normal)
    val label = TextStyle(fontFamily = Bricolage, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    val secondary = TextStyle(fontFamily = Bricolage, fontSize = 15.sp, fontWeight = FontWeight.Normal)
}

// ---- Shape and spacing (DESIGN.md section 4) -------------------------------------------

object Spacing {
    val grid = 4.dp
    val screen = 20.dp
    val cardGap = 10.dp
    /** Card padding runs 16-18. */
    val cardPadding = 16.dp
    val cardPaddingLarge = 18.dp
    val minTouchTarget = 48.dp
    val buttonHeight = minTouchTarget
}

object Radius {
    val card = 20.dp
    val button = 12.dp
    val input = button
    val glyphChip = 16.dp
    /** Bars run 5-6. */
    val barNarrow = 5.dp
    val bar = 6.dp
}

// ---- Theme -----------------------------------------------------------------------------

private val LocalColors = staticCompositionLocalOf { LightColors }

/** Access to the current theme's roles from any composable. */
object Overgrown {
    val colors: OvergrownColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
}

// Material components read these slots; every slot maps to one of the six styles.
private val MaterialType = Typography(
    displayLarge = Type.display,
    displayMedium = Type.display,
    displaySmall = Type.title,
    headlineLarge = Type.title,
    headlineMedium = Type.title,
    headlineSmall = Type.cardTitle,
    titleLarge = Type.cardTitle,
    titleMedium = Type.label,
    titleSmall = Type.label,
    bodyLarge = Type.body,
    bodyMedium = Type.body,
    bodySmall = Type.secondary,
    labelLarge = Type.label,
    labelMedium = Type.secondary,
    labelSmall = Type.secondary,
)

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.input),
    small = RoundedCornerShape(Radius.button),
    medium = RoundedCornerShape(Radius.button),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.card),
)

private fun materialScheme(c: OvergrownColors, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    primary = c.ink,
    onPrimary = c.onInk,
    secondaryContainer = StateBlock.Moss.fill,
    onSecondaryContainer = StateBlock.Moss.text,
    background = c.ground,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.surface,
    onSurfaceVariant = c.muted,
    surfaceContainerLowest = c.surface,
    surfaceContainerLow = c.surface,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.surface,
    outline = c.outline,
    outlineVariant = c.outline,
    error = c.errorText,
    errorContainer = StateBlock.Brick.fill,
    onErrorContainer = StateBlock.Brick.text,
)

/** The app theme. No dynamic (wallpaper) colour. */
@Composable
fun OvergrownTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalColors provides colors) {
        MaterialTheme(colorScheme = materialScheme(colors, dark), typography = MaterialType, shapes = MaterialShapes, content = content)
    }
}
