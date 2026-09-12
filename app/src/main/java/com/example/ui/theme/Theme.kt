package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BluePrimaryDark,
    secondary = BlueSecondaryDark,
    tertiary = BlueTertiaryDark,
    background = BlueBackgroundDark,
    surface = BlueSurfaceDark,
    onBackground = BlueOnBackgroundDark,
    onSurface = BlueOnSurfaceDark,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF0F172A),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF0F172A),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF334155)
)

private val LightColorScheme = lightColorScheme(
    primary = BluePrimaryLight,
    secondary = BlueSecondaryLight,
    tertiary = BlueTertiaryLight,
    background = BlueBackgroundLight,
    surface = BlueSurfaceLight,
    onBackground = BlueOnBackgroundLight,
    onSurface = BlueOnSurfaceLight,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFCBD5E1)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Disable dynamic color so user's beautiful Blue palette is applied explicitly to all Android versions
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
