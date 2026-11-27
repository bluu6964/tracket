package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    // Professional B/W language — white primary, refined dark surfaces.
    primary = AmMint,
    onPrimary = AmInk,
    primaryContainer = AmPanelHigh,
    onPrimaryContainer = TextPrimary,
    secondary = AmCyan,
    onSecondary = TextPrimary,
    background = AmBase,
    onBackground = TextPrimary,
    surface = AmPanel,
    onSurface = TextPrimary,
    surfaceVariant = AmPanelHigh,
    onSurfaceVariant = TextSecondary,
    outline = AmHairlineStrong,
    inverseSurface = Color(0xFF151B23),
    inverseOnSurface = Color(0xFFF4F4F5),
    error = AmDanger,
    onError = AmInk
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
                window.statusBarColor = AmBase.toArgb()
                window.navigationBarColor = AmBase.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        // Soft professional radii (CapCut-like). Functional circles (play button,
        // thumbs, colour discs) remain fully round and are not driven by these roles.
        shapes = Shapes(
            extraSmall = RoundedCornerShape(AmCornerMicro),
            small = RoundedCornerShape(AmCornerChip),
            medium = RoundedCornerShape(AmCornerItem),
            large = RoundedCornerShape(AmCornerPanel),
            extraLarge = RoundedCornerShape(AmCornerCard)
        ),
        content = content
    )
}
