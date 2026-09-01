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
    onSecondary = Color.White,
    tertiary = Color(0xFF765A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE08A),
    onTertiaryContainer = Color(0xFF241A00),
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE0E5E1),
    onSurfaceVariant = Color(0xFF414844),
    outline = Color(0xFF717873),
    outlineVariant = Color(0xFFC0C9C3),
    error = Rose,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7D1BA),
    onPrimary = Color(0xFF0A281F),
    primaryContainer = Color(0xFF244E3E),
    onPrimaryContainer = Color(0xFFC3EED5),
    secondary = Color(0xFFB2CCBD),
    onSecondary = Color(0xFF1E352B),
    secondaryContainer = Color(0xFF344B40),
    onSecondaryContainer = Color(0xFFCDE7D8),
    tertiary = Color(0xFFF4C64F),
    onTertiary = Color(0xFF3D2F00),
    tertiaryContainer = Color(0xFF594500),
    onTertiaryContainer = Color(0xFFFFE17C),
    background = Color(0xFF101713),
    onBackground = Color(0xFFE1E8E3),
    surface = Color(0xFF18211E),
    onSurface = Color(0xFFE1E8E3),
    surfaceVariant = Color(0xFF3F4943),
    onSurfaceVariant = Color(0xFFBEC9C1),
    outline = Color(0xFF89938D),
    outlineVariant = Color(0xFF3F4943),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun LabelWiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
