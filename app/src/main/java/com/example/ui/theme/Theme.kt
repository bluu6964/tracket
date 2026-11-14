package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    // B/W language (2026-11-13 order): Material's accent roles ride the monochrome
    // token layer (white primary, near-white secondary — theme/DesignTokens.kt).
    primary = AmMint,
    onPrimary = AmInk,
    primaryContainer = EditorSurfaceVariant,
    onPrimaryContainer = TextPrimary,
    secondary = AmCyan,
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
fun AfterMotionTheme(
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
        // SQUARE law (Sarowar 2026-11-14): every Material shape role is sharp
        // square. Shapes roles are typed CornerBasedShape, so the square value
        // is a zero corner radius (RectangleShape is not a CornerBasedShape).
        // (Material3 dialogs and sheets use their own token shapes, so those
        // call sites also pass shape = RectangleShape explicitly.)
        shapes = Shapes(
            extraSmall = RoundedCornerShape(0.dp),
            small = RoundedCornerShape(0.dp),
            medium = RoundedCornerShape(0.dp),
            large = RoundedCornerShape(0.dp),
            extraLarge = RoundedCornerShape(0.dp)
        ),
        content = content
    )
}
