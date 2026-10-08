package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = NeonCyan,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = EditorSurfaceVariant,
    onPrimaryContainer = TextPrimary,
    secondary = ElectricBlue,
    onSecondary = TextPrimary,
    background = EditorBackground,
    onBackground = TextPrimary,
    surface = EditorSurface,
    onSurface = TextPrimary,
    surfaceVariant = EditorSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = EditorDivider
)

@Composable
fun TracketTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = EditorBackground.toArgb()
                window.navigationBarColor = EditorBackground.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
