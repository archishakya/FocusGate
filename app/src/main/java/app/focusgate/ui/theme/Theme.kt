package app.focusgate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Violet = Color(0xFF6C4DFF)
val VioletDark = Color(0xFFC9BFFF)
val Lime = Color(0xFFB7F23C)
val LimeDeep = Color(0xFF3F6B00)
val GoGreen = Color(0xFF1FA85A)
val StopRed = Color(0xFFE5484D)

private val LightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E1FF),
    onPrimaryContainer = Color(0xFF1D0A66),
    secondary = Color(0xFF5B5672),
    tertiary = LimeDeep,
    tertiaryContainer = Lime,
    onTertiaryContainer = Color(0xFF1A2A00),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1B1A22),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1B1A22),
    surfaceVariant = Color(0xFFEDE9F7),
    onSurfaceVariant = Color(0xFF4A4658),
    error = StopRed,
)

private val DarkColors = darkColorScheme(
    primary = VioletDark,
    onPrimary = Color(0xFF2B1A8F),
    primaryContainer = Color(0xFF4430C4),
    onPrimaryContainer = Color(0xFFE7E1FF),
    secondary = Color(0xFFC9C3E0),
    tertiary = Lime,
    tertiaryContainer = Color(0xFF2E4A00),
    onTertiaryContainer = Lime,
    background = Color(0xFF110F18),
    onBackground = Color(0xFFE8E5F0),
    surface = Color(0xFF110F18),
    onSurface = Color(0xFFE8E5F0),
    surfaceVariant = Color(0xFF211F2B),
    onSurfaceVariant = Color(0xFFC9C5D6),
    error = Color(0xFFFF8A8E),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Theme preference values stored in Store: SYSTEM, LIGHT, DARK. */
@Composable
fun isDarkFor(pref: String): Boolean = when (pref) {
    "DARK" -> true
    "LIGHT" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun FocusGateTheme(themePref: String, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDarkFor(themePref)) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
