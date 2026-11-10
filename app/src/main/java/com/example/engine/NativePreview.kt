package com.example.engine

/**
 * The editor preview is always drawn by the C++ engine ([NativePreviewEngine]): video / photo layers, text, shapes and
 * stickers, with blend modes, 3D, crop, cutout and face blur, exactly like the export. The old Media3 preview is gone
 * (v0.1.56), and so is the switch. minSdk is 30, so every phone has the photo decoder, the system font matcher and AAudio.
 *
 * The only thing C++ cannot draw yet is an emoji on a phone without a colour bitmap emoji font ([nativeTextEligible]):
 * that one text layer is drawn by Compose on top of the picture (without its blend mode).
 */
object NativePreview {
    /** False only when libam_engine.so could not be loaded (the app then has no preview at all). */
    val available: Boolean get() = DecoderPool.isAvailable
}
