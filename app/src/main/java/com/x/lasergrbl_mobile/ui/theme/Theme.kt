package com.x.lasergrbl_mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = LaserBlueDark,
    secondary = Color(0xFF9AD29F),
    tertiary = LaserRedDark,
    background = Color(0xFF0F1115),
    surface = PanelDark,
    onPrimary = Color(0xFF08264D),
    onSecondary = Color(0xFF0C2D13),
    onTertiary = Color(0xFF4A0804),
    onBackground = Color(0xFFE7EAF0),
    onSurface = Color(0xFFE7EAF0),
)

private val LightColorScheme = lightColorScheme(
    primary = LaserBlue,
    secondary = WorkGreen,
    tertiary = LaserRed,
    background = Color(0xFFFFFFFF),
    surface = PanelLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF15181E),
    onSurface = Color(0xFF15181E),
)

@Composable
fun LaserGRBLMobileTheme(
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
