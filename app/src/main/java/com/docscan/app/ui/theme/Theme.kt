package com.docscan.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = SlateBackground,
    primaryContainer = EmeraldDark,
    secondary = TealAccent,
    background = SlateBackground,
    surface = SlateSurface,
    onBackground = TextLightPrimary,
    onSurface = TextLightPrimary,
    outline = SlateBorder,
    error = DangerRed
)

@Composable
fun DocScanTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
