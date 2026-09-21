package com.dhoom.bt

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    secondary = Color(0xFF2C7A7B),
    background = Color(0xFF0F1115),
    surface = Color(0xFF1A1D23)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00838F),
    secondary = Color(0xFF4FD1C5),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun DHooMTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
