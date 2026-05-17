package dev.governance.android.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val OakDarkScheme = darkColorScheme(
    primary = OakPalette.Primary,
    onPrimary = OakPalette.OnPrimary,
    primaryContainer = OakPalette.PrimaryContainer,
    onPrimaryContainer = OakPalette.OnPrimaryContainer,
    secondary = OakPalette.Secondary,
    onSecondary = OakPalette.OnSecondary,
    secondaryContainer = OakPalette.SecondaryContainer,
    onSecondaryContainer = OakPalette.OnSecondaryContainer,
    background = OakPalette.Background,
    onBackground = OakPalette.TextPrimary,
    surface = OakPalette.Surface,
    onSurface = OakPalette.TextPrimary,
    surfaceVariant = OakPalette.SurfaceVariant,
    onSurfaceVariant = OakPalette.TextSecondary,
    error = OakPalette.Error,
    errorContainer = OakPalette.ErrorContainer,
    outline = OakPalette.Outline,
    outlineVariant = OakPalette.OutlineVariant,
    surfaceContainerHighest = OakPalette.SurfaceElevated,
    surfaceContainerHigh = OakPalette.SurfaceVariant,
    surfaceContainer = OakPalette.Surface,
    surfaceContainerLow = OakPalette.Surface,
    surfaceContainerLowest = OakPalette.Background,
)

private val OakTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp,
    ),
)

/**
 * Oak & Sparrow dark theme — custom brand palette, tighter typography.
 * Always dark. The dark scheme is the brand identity.
 */
@Composable
fun OakSparrowTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = OakDarkScheme,
        typography = OakTypography,
        content = content,
    )
}
