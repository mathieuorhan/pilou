package fr.priseok.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.priseok.app.R

// Palette: a mint pharmacy-shelf calm, and the two candy halves of the capsule.
@Immutable
data class Palette(
    val mist: Color,      // background
    val surface: Color,   // raised areas
    val pine: Color,      // ink and primary action
    val pineSoft: Color,  // secondary text
    val line: Color,      // hairlines and outlines
    val pink: Color,      // capsule half
    val butter: Color,    // capsule half
    val leaf: Color,      // taken
    val coral: Color,     // missed
    val onPine: Color,
)

val LightPalette = Palette(
    mist = Color(0xFFEAF4F1),
    surface = Color(0xFFFFFFFF),
    pine = Color(0xFF17332D),
    pineSoft = Color(0xFF4F6B64),
    line = Color(0xFFCFE0DA),
    pink = Color(0xFFFF8EA1),
    butter = Color(0xFFFFD36E),
    leaf = Color(0xFF2FA66A),
    coral = Color(0xFFE2553E),
    onPine = Color(0xFFEAF4F1),
)

val DarkPalette = Palette(
    mist = Color(0xFF0F201C),
    surface = Color(0xFF182D28),
    pine = Color(0xFFDDF0EA),
    pineSoft = Color(0xFF9DB8B0),
    line = Color(0xFF2B4640),
    pink = Color(0xFFFF8EA1),
    butter = Color(0xFFFFD36E),
    leaf = Color(0xFF4CC285),
    coral = Color(0xFFFF7A62),
    onPine = Color(0xFF0F201C),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val Fredoka = FontFamily(
    Font(R.font.fredoka_500, FontWeight.Medium),
    Font(R.font.fredoka_600, FontWeight.SemiBold),
)

private val AppTypography = Typography(
    displayMedium = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp),
    headlineMedium = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.Medium, fontSize = 18.sp),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp),
)

@Composable
fun PriseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkPalette else LightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.pine, onPrimary = p.onPine,
        background = p.mist, onBackground = p.pine,
        surface = p.surface, onSurface = p.pine,
        surfaceContainerHigh = p.surface, surfaceContainerHighest = p.surface,
        onSurfaceVariant = p.pineSoft, outline = p.line, outlineVariant = p.line,
        secondaryContainer = p.butter, onSecondaryContainer = Color(0xFF17332D),
        error = p.coral,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
