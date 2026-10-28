package com.vanu.comicpage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFC93C)
val Pink = Color(0xFFFF5C7A)
val Teal = Color(0xFF2EC4B6)
val SurfaceDark = Color(0xFF0F0E14)
val CardDark = Color(0xFF1B1A22)
val CardElevated = Color(0xFF282731)
val TextPrimary = Color(0xFFF2F2F5)
val TextSecondary = Color(0xFFA6A5B3)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    secondary = Teal,
    onSecondary = Color.Black,
    background = SurfaceDark,
    onBackground = TextPrimary,
    surface = CardDark,
    onSurface = TextPrimary,
    surfaceVariant = CardElevated,
    onSurfaceVariant = TextSecondary,
    error = Pink,
    onError = Color.White,
)

@Composable
fun ComicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
