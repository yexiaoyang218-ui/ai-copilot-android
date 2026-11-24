package com.aisocial.copilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisocial.copilot.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5F86),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E3F5),
    onPrimaryContainer = Color(0xFF0B1D33),
    secondary = Color(0xFF566372),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE4EE),
    onSecondaryContainer = Color(0xFF131C25),
    tertiary = Color(0xFF6B5E7D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8DDF4),
    onTertiaryContainer = Color(0xFF221933),
    background = Color(0xFFF6F7FA),
    onBackground = Color(0xFF14171B),
    surface = Color(0xFFFDFDFF),
    onSurface = Color(0xFF14171B),
    surfaceVariant = Color(0xFFE7EBF1),
    onSurfaceVariant = Color(0xFF444C56),
    surfaceTint = Color(0xFF3F5F86),
    outline = Color(0xFFB3BCC7),
    outlineVariant = Color(0xFFD8DEE6),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C3E8),
    onPrimary = Color(0xFF11253C),
    primaryContainer = Color(0xFF2B4260),
    onPrimaryContainer = Color(0xFFD7E3F5),
    secondary = Color(0xFFB7C4D4),
    onSecondary = Color(0xFF1E2A36),
    secondaryContainer = Color(0xFF33404D),
    onSecondaryContainer = Color(0xFFDCE4EE),
    tertiary = Color(0xFFCBBEDD),
    onTertiary = Color(0xFF322745),
    tertiaryContainer = Color(0xFF4A3C60),
    onTertiaryContainer = Color(0xFFE8DDF4),
    background = Color(0xFF111417),
    onBackground = Color(0xFFE3E6EA),
    surface = Color(0xFF171A1E),
    onSurface = Color(0xFFE3E6EA),
    surfaceVariant = Color(0xFF262B31),
    onSurfaceVariant = Color(0xFFB6BEC9),
    surfaceTint = Color(0xFFA8C3E8),
    outline = Color(0xFF6C757E),
    outlineVariant = Color(0xFF3A4149),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
)

@Composable
fun AiCopilotTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
