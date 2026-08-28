package com.cvkulkarnidev.foodlabel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Forest = Color(0xFF16362D)
val Sage = Color(0xFF3E6B58)
val Amber = Color(0xFFF4B942)
val Cream = Color(0xFFF8F5EC)
val Ink = Color(0xFF18211E)
val SoftGreen = Color(0xFFE4EFE8)
val Rose = Color(0xFFB44343)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = SoftGreen,
    onPrimaryContainer = Forest,
    secondary = Sage,
    tertiary = Amber,
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    error = Rose,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7D1BA),
    onPrimary = Color(0xFF0A281F),
    primaryContainer = Color(0xFF244E3E),
    secondary = Color(0xFFB2CCBD),
    tertiary = Amber,
    background = Color(0xFF101713),
    surface = Color(0xFF18211E),
)

@Composable
fun LabelWiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

