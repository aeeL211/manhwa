package com.shinigami.client.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryAccent,
    background = DarkBackground,
    surface = SurfaceDark,
    surfaceContainer = SurfaceContainerDark,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
)

@Composable
fun ShinigamiTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
