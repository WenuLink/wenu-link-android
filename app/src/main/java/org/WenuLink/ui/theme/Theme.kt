package org.WenuLink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// DarkTheme
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = NeutralWhite,
    primaryContainer = PrimaryBlueDark,
    onPrimaryContainer = NeutralWhite,

    background = NeutralBlack,
    onBackground = NeutralWhite,

    surface = NeutralDarkGray,
    onSurface = NeutralWhite,

    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFCCCCCC),

    error = StatusRed,
    outline = Color(0xFF444444)
)

// LightTheme
private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlueDark,
    onPrimary = NeutralWhite,
    primaryContainer = PrimaryBlue,
    onPrimaryContainer = NeutralWhite,

    background = NeutralLightGray,
    onBackground = NeutralBlack,

    surface = NeutralWhite,
    onSurface = NeutralBlack,

    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF444444),

    error = StatusRed,
    outline = Color(0xFF888888)
)

@Composable
fun WenuLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
