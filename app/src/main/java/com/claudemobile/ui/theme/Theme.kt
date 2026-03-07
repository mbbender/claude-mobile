package com.claudemobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val ClaudeOrange = Color(0xFFDA7756)
private val ClaudeDark = Color(0xFF1A1A2E)
private val ClaudeSurface = Color(0xFF16213E)

private val DarkColorScheme = darkColorScheme(
    primary = ClaudeOrange,
    onPrimary = Color.White,
    secondary = Color(0xFF8B9DC3),
    background = ClaudeDark,
    surface = ClaudeSurface,
    surfaceVariant = Color(0xFF1F2B47),
    onBackground = Color(0xFFE8E8E8),
    onSurface = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3A4A6B)
)

private val LightColorScheme = lightColorScheme(
    primary = ClaudeOrange,
    onPrimary = Color.White,
    secondary = Color(0xFF5B6E8F),
    background = Color(0xFFF8F6F4),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0ECE8),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF5A5A5A),
    outline = Color(0xFFD0C8C0)
)

@Composable
fun ClaudeMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
