package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldPrimaryDark,
    onPrimary = EmeraldOnPrimaryDark,
    primaryContainer = EmeraldPrimaryContainerDark,
    onPrimaryContainer = EmeraldOnPrimaryContainerDark,
    secondary = TealSecondaryContainer,
    onSecondary = TealOnSecondaryContainer,
    tertiary = AmberTertiaryContainer,
    onTertiary = AmberOnTertiaryContainer,
    background = SchoolBackgroundDark,
    onBackground = Color(0xFFE2E8E3),
    surface = SchoolSurfaceDark,
    onSurface = Color(0xFFE2E8E3),
    surfaceVariant = SchoolSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFC1CBC3)
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = EmeraldOnPrimary,
    primaryContainer = EmeraldPrimaryContainer,
    onPrimaryContainer = EmeraldOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = AmberTertiary,
    onTertiary = AmberOnTertiary,
    tertiaryContainer = AmberTertiaryContainer,
    onTertiaryContainer = AmberOnTertiaryContainer,
    background = SchoolBackgroundLight,
    onBackground = Color(0xFF171D19),
    surface = SchoolSurfaceLight,
    onSurface = Color(0xFF171D19),
    surfaceVariant = SchoolSurfaceVariantLight,
    onSurfaceVariant = Color(0xFF414942),
    outline = SchoolOutlineLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
