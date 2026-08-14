package com.liustudio.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E7FF),
    onPrimaryContainer = Color(0xFF001A40),
    secondary = Color(0xFF52606F),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0F4),
    onSurface = Color(0xFF1B1D21),
    onSurfaceVariant = Color(0xFF5B616A),
    outline = Color(0xFF747A83)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF0D477D),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFFBAC7D6),
    background = Color(0xFF111316),
    surface = Color(0xFF191C20),
    surfaceVariant = Color(0xFF282C31),
    onSurface = Color(0xFFE3E3E6),
    onSurfaceVariant = Color(0xFFC3C6CD),
    outline = Color(0xFF8D9199)
)

@Composable
fun LocalAssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
