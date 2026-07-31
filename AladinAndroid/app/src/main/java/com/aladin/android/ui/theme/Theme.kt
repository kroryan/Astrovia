package com.aladin.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = AladinBlue,
    secondary = AladinGold,
    background = SkyBlack,
    surface = SkyNight,
    onPrimary = TextPrimary,
    onSecondary = SkyBlack,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun AladinTheme(content: @Composable () -> Unit) {
    // El modo edge-to-edge se activa en MainActivity (enableEdgeToEdge); las barras
    // del sistema quedan transparentes sobre el fondo oscuro del cielo.
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography,
        content = content,
    )
}