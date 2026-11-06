package com.inkwell.notes.ui.theme

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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.ThemeMode

/**
 * Inkwell design tokens. Restrained, content-first: warm paper ground, near-black ink,
 * one ink-blue accent, and a recording red used only for live recording state.
 */
@Immutable
data class InkwellColors(
    val ground: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val outline: Color,
    val ink: Color,
    val inkMuted: Color,
    val accent: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val record: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Canvas backdrop behind pages. */
    val desk: Color,
    val pageShadow: Color,
    val selection: Color,
    val isDark: Boolean,
    /**
     * Eight restrained, mutually distinguishable voice colours (dots, chips, waveform lanes),
     * indexed by Person.colorIndex / RecordingSpeaker.colorIndex. Never used for state.
     */
    val speakers: List<Color> = LightSpeakerPalette,
) {
    /** The palette colour for [index]; any int (negative or past the end) wraps around. */
    fun speaker(index: Int): Color = speakers[Math.floorMod(index, speakers.size)]
}

/**
 * Voice colours on light grounds: muted, mid-tone, at least 4:1 against white (3.5:1 on the
 * accent tint of a selected row) so a 10dp dot reads, and spread around the hue circle (teal, ochre, violet, slate blue, rose, green,
 * brown, slate). Red is left out: it means "recording".
 */
val LightSpeakerPalette = listOf(
    Color(0xFF1F8379),
    Color(0xFFA86F12),
    Color(0xFF8250AA),
    Color(0xFF3F67B0),
    Color(0xFFB8486F),
    Color(0xFF55822A),
    Color(0xFF86643F),
    Color(0xFF5F6977),
)

/** The same voices for dark grounds: lighter and a little softer, at least 5:1 on the dark surfaces. */
val DarkSpeakerPalette = listOf(
    Color(0xFF6CC4B8),
    Color(0xFFE1B262),
    Color(0xFFC39BE2),
    Color(0xFF8FB0EA),
    Color(0xFFEE93B2),
    Color(0xFF9DCA74),
    Color(0xFFCDAB86),
    Color(0xFFA9B2C0),
)

val LightInkwell = InkwellColors(
    ground = Color(0xFFF5F4F0),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFEDEBE5),
    outline = Color(0xFFDDD9CF),
    ink = Color(0xFF1A1916),
    inkMuted = Color(0xFF57544C),
    accent = Color(0xFF2952CC),
    accentContainer = Color(0xFFE3E9FB),
    onAccent = Color(0xFFFFFFFF),
    record = Color(0xFFC93A2E),
    success = Color(0xFF2E7D4F),
    warning = Color(0xFF8A5A00),
    danger = Color(0xFFB3261E),
    desk = Color(0xFFE9E7E1),
    pageShadow = Color(0x1F1A1916),
    selection = Color(0xFF2952CC),
    isDark = false,
    speakers = LightSpeakerPalette,
)

val DarkInkwell = InkwellColors(
    ground = Color(0xFF141412),
    surface = Color(0xFF1D1C1A),
    surfaceAlt = Color(0xFF262522),
    outline = Color(0xFF3A3833),
    ink = Color(0xFFEDEBE5),
    inkMuted = Color(0xFFA9A59B),
    accent = Color(0xFF8FA8F0),
    accentContainer = Color(0xFF22305A),
    onAccent = Color(0xFF0E1A3D),
    record = Color(0xFFF0675A),
    success = Color(0xFF6CC28E),
    warning = Color(0xFFE0B05A),
    danger = Color(0xFFF2B8B5),
    desk = Color(0xFF0F0F0E),
    pageShadow = Color(0x66000000),
    selection = Color(0xFF8FA8F0),
    isDark = true,
    speakers = DarkSpeakerPalette,
)

val LocalInkwellColors = staticCompositionLocalOf { LightInkwell }

object Inkwell {
    val colors: InkwellColors @Composable get() = LocalInkwellColors.current
}

val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

private fun t(size: Int, weight: FontWeight, line: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = Figtree, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp,
)

val InkwellTypography = Typography(
    displaySmall = t(32, FontWeight.SemiBold, 40, -0.4f),
    headlineMedium = t(26, FontWeight.SemiBold, 32, -0.3f),
    headlineSmall = t(22, FontWeight.SemiBold, 28, -0.2f),
    titleLarge = t(18, FontWeight.SemiBold, 24, -0.1f),
    titleMedium = t(15, FontWeight.SemiBold, 20),
    titleSmall = t(13, FontWeight.SemiBold, 18),
    bodyLarge = t(15, FontWeight.Normal, 22),
    bodyMedium = t(14, FontWeight.Normal, 20),
    bodySmall = t(12, FontWeight.Normal, 16),
    labelLarge = t(14, FontWeight.Medium, 18),
    labelMedium = t(12, FontWeight.Medium, 16),
    labelSmall = t(11, FontWeight.Medium, 14, 0.2f),
)

val InkwellShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Spacing and sizing scale (dp). */
object Dimens {
    val gutter = 16.dp
    val toolbarHeight = 44.dp
    val toolButton = 36.dp
    val icon = 20.dp
    val topBarHeight = 48.dp
    val railWidth = 232.dp
    val panelWidth = 420.dp
    val minTouch = 44.dp
    /** Visible width of the collapsed recording sliver. */
    val recorderSliver = 8.dp
    /** Invisible touch target of the collapsed recording sliver. */
    val recorderSliverTouch = 32.dp
}

/** Whether [mode] shows the dark palette; follows the system setting live for [ThemeMode.SYSTEM]. */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun InkwellTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    val c = if (dark) DarkInkwell else LightInkwell
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentContainer, onPrimaryContainer = c.ink,
            secondary = c.inkMuted, onSecondary = c.ground, secondaryContainer = c.surfaceAlt, onSecondaryContainer = c.ink,
            background = c.ground, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.inkMuted, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceAlt, surfaceContainerLow = c.ground, outline = c.outline, outlineVariant = c.outline,
            error = c.danger, tertiary = c.record,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentContainer, onPrimaryContainer = c.ink,
            secondary = c.inkMuted, onSecondary = c.surface, secondaryContainer = c.surfaceAlt, onSecondaryContainer = c.ink,
            background = c.ground, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.inkMuted, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceAlt, surfaceContainerLow = c.ground, outline = c.outline, outlineVariant = c.outline,
            error = c.danger, tertiary = c.record,
        )
    }
    CompositionLocalProvider(LocalInkwellColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = InkwellTypography, shapes = InkwellShapes, content = content)
    }
}
