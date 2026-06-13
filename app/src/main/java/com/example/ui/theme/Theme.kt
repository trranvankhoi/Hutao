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

private val RemoteDarkColorScheme = darkColorScheme(
    primary = ImmersiveAccent,
    secondary = ImmersiveActiveGreen,
    background = ImmersiveBg,
    surface = ImmersiveSurface,
    onPrimary = ImmersiveButtonDeep,
    onSecondary = ImmersiveBg,
    onBackground = ImmersiveTextMain,
    onSurface = ImmersiveTextMain,
    outline = ImmersiveBorder
)

private val RemoteLightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    secondary = AccentCyan,
    background = androidx.compose.ui.graphics.Color(0xFFF3F6FA),
    surface = androidx.compose.ui.graphics.Color.White,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onSecondary = androidx.compose.ui.graphics.Color.Black,
    onBackground = androidx.compose.ui.graphics.Color(0xFF1F2937),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1F2937),
    outline = androidx.compose.ui.graphics.Color(0xFFE5E7EB)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Allow turning dynamicColor off for professional static dark styling control
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> RemoteDarkColorScheme
        else -> RemoteLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
