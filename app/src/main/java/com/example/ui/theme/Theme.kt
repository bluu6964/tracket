package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = EditorBackground,
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
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp)
        ),
        content = content
    )
}
