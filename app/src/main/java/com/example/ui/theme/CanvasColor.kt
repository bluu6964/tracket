package com.example.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Background colour of the canvas (set in the Aspect Ratio sheet). */
val LocalCanvasColor = compositionLocalOf { CanvasBackground }

fun Long.toCanvasColor(): Color = Color(this)
