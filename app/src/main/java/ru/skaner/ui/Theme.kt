package ru.skaner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Indigo = Color(0xFF4F5BFF)
val Violet = Color(0xFF7B3FE4)

/** Фирменный градиент: кнопка «Сканировать», пустой экран, иконка. */
val BrandGradient = Brush.linearGradient(listOf(Color(0xFF5B6CFF), Violet))

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E4FF),
    onPrimaryContainer = Color(0xFF0B1366),
    secondary = Color(0xFF5D5F7A),
    secondaryContainer = Color(0xFFE6E7F4),
    onSecondaryContainer = Color(0xFF1A1B2E),
    tertiary = Violet,
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF14151C),
    surface = Color(0xFFF5F6FB),
    onSurface = Color(0xFF14151C),
    onSurfaceVariant = Color(0xFF5E6070),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEDEEF6),
    surfaceContainerHigh = Color(0xFFE7E8F2),
    surfaceContainerHighest = Color(0xFFE1E2EE),
    outlineVariant = Color(0xFFDADBE7),
    error = Color(0xFFD93848),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9EA6FF),
    onPrimary = Color(0xFF101A8C),
    primaryContainer = Color(0xFF2E38B8),
    onPrimaryContainer = Color(0xFFE2E4FF),
    secondary = Color(0xFFC4C5DD),
    secondaryContainer = Color(0xFF2C2E3F),
    onSecondaryContainer = Color(0xFFE2E3F5),
    tertiary = Color(0xFFC9A8FF),
    background = Color(0xFF0E1016),
    onBackground = Color(0xFFE5E6EF),
    surface = Color(0xFF0E1016),
    onSurface = Color(0xFFE5E6EF),
    onSurfaceVariant = Color(0xFFA3A5B6),
    surfaceContainerLowest = Color(0xFF0A0B10),
    surfaceContainerLow = Color(0xFF171922),
    surfaceContainer = Color(0xFF1B1D27),
    surfaceContainerHigh = Color(0xFF22242F),
    surfaceContainerHighest = Color(0xFF2B2D39),
    outlineVariant = Color(0xFF30323F),
    error = Color(0xFFFF6B78),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SkanerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
