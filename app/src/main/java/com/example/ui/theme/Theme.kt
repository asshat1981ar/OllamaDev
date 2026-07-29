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

import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = ImmersivePrimary,
    secondary = ImmersiveSecondary,
    tertiary = ImmersivePrimary,
    background = ImmersiveBackground,
    surface = ImmersiveSurface,
    surfaceVariant = ImmersiveSurfaceVariant,
    onPrimary = Color(0xFF21005D),
    onSecondary = Color.White,
    onBackground = ImmersiveOnBackground,
    onSurface = ImmersiveOnSurface,
    outline = ImmersiveBorder
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme by default for the premium cyber vibe
  dynamicColor: Boolean = false, // Use our handcrafted design tokens rather than system dynamic colors
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
