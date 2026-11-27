package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewStream
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ToolRowSplit
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.theme.AmAccentViolet
import com.example.ui.theme.AmBarSurface
import com.example.ui.theme.AmCardSurface
import com.example.ui.theme.AmDanger
import com.example.ui.theme.AmHairlineStrong
import com.example.ui.theme.AmPanel
import com.example.ui.theme.AmSpace
import com.example.ui.theme.AmTextDim
import com.example.ui.theme.AmTextPrimary
import com.example.ui.theme.IconActive
import com.example.ui.theme.TextPrimary
import com.example.ui.util.bouncyClickable

/**
 * The editor DOCK (build 217 — Sarowar's HTML reference, copied
 * exactly): a top hairline (#1E1E24), then a horizontally scrolling row
 * of tiles. A tile = a square box (surface fill, 1 dp white-at-4%
 * border) with its glyph, and the label UNDER it (11 sp/600, muted).
 * Build 239 (Sarowar): the tiles shrink 58 → 46 dp (r14 → r11, glyphs
 * scaled in proportion) so the dock is SHORTER and more timeline lanes
 * fit above it — same twelve tiles, same order, same layout otherwise.
 *
 * Build 218 (Sarowar's HTML reference v3): the dock is the ALL-LAYER add
 * dock — exactly TWELVE tiles in the reference's order: Video/Photo,
 * Music, Fx, Text, Shapes, Sticker, Vector, Drawing, Null, 3D Camera,
 * Group, Chain. Every tile performs its own add directly (build 217's
 * arrangement, where Video/Photo opened the Add-layer sheet, is
 * replaced); Fx keeps its job — it opens the effects stage, it is not a
 * layer add. Twelve tiles are wider than the screen, so the row scrolls,
 * exactly as the reference's dock does (its overflow-x: auto).
 *
 * Build 249 (UI migration stage 2): the scrolling row is now ONE native
 * GL surface ([DockGlRow] in DockSurface.kt) — tiles, glyphs and labels
 * drawn from model/DockGeometry's law, the three Material-icon glyphs and
 * all labels blitted from the UI atlas; invisible Compose boxes keep the
 * tap targets, testTags and press animation. Same tiles, order, sizes
 * and actions; [ChainAddPanel] below is untouched.
 */
@Composable
fun BottomToolbar(
    height: Dp,
    onMediaClick: () -> Unit,
    onAudioClick: () -> Unit,
    onFxClick: () -> Unit,
    onTextClick: () -> Unit,
    onShapesClick: () -> Unit,
    onNullClick: () -> Unit,
    modifier: Modifier = Modifier,
    fxActive: Boolean = false,
    /** Build 218: the new tiles' direct adds. */
    onStickerClick: () -> Unit = {},
    onVectorClick: () -> Unit = {},
    onDrawingClick: () -> Unit = {},
    onCameraClick: () -> Unit = {},
    onGroupClick: () -> Unit = {},
    onChainClick: () -> Unit = {},
    /** Build 262: the 3D Space tile's direct add. */
    onSpaceClick: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(AmBarSurface)
            .testTag("dock_toolbar")
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AmCardSurface)
        )
        // Build 276 (fresh design): the dock row sits in a rounded
        // slate container — a floating band inside the same dock
        // height; the row's own geometry is untouched.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .background(AmPanel, RoundedCornerShape(20.dp))
                .border(1.dp, AmHairlineStrong, RoundedCornerShape(20.dp))
        ) {
        DockGlRow(
            modifier = Modifier.fillMaxSize(),
            fxActive = fxActive,
            onMediaClick = onMediaClick,
            onAudioClick = onAudioClick,
            onFxClick = onFxClick,
            onTextClick = onTextClick,
            onShapesClick = onShapesClick,
            onNullClick = onNullClick,
            onStickerClick = onStickerClick,
            onVectorClick = onVectorClick,
            onDrawingClick = onDrawingClick,
            onCameraClick = onCameraClick,
            onGroupClick = onGroupClick,
            onChainClick = onChainClick,
            onSpaceClick = onSpaceClick
        )
        }
    }
}

/**
 * Shown while a Chain layer is selected: only ways to put layers INTO the chain (the chain has no picture of its own, so
 * no Effects / Transform / Animations here). A back arrow leaves the chain, the bin deletes it with the layers inside.
 *
 * Build 202: the same primary-row split as the clip panels (model/ToolRowSplit)
 * — the row no longer scrolls and carries no More button; the overflow is
 * published to the one tools grid (a layout-flow sheet since build 206).
 */
@Composable
fun ChainAddPanel(
    height: Dp,
    onBack: () -> Unit,
    onMedia: () -> Unit,
    onText: () -> Unit,
    onShape: () -> Unit,
    onAudio: () -> Unit,
    onStickers: () -> Unit,
    onNull: () -> Unit,
    onController: () -> Unit,
    onCamera: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** The tools grid's bridge (build 202): this panel's overflow actions are published here. */
    morePanel: MorePanelState? = null,
    /**
     * Build 210 (TOOLS PANEL LAW): while the unified tools panel is open
     * it carries ALL of this panel's actions in its one scrolling list —
     * the panel publishes its full list and the footer bar renders only
     * the back button. False = build 202's split behaviour.
     * Build 211: the footer bar renders NOTHING while the panel is open
     * (the back strip is gone too — see the early return below).
     */
    toolsInGrid: Boolean = false
) {
    val actions = listOf(
        ChainAction("Media", Icons.Default.AddPhotoAlternate, "chain_media", onMedia),
        ChainAction("Text", Icons.Default.TextFields, "chain_text", onText),
        ChainAction("Shape", Icons.Default.Category, "chain_shape", onShape),
        ChainAction("Audio", Icons.Default.MusicNote, "chain_audio", onAudio),
        ChainAction("Stickers", Icons.Default.SentimentSatisfiedAlt, "chain_stickers", onStickers),
        ChainAction("Null", Icons.Default.CenterFocusWeak, "chain_null", onNull),
        ChainAction("Controller", Icons.Default.Tune, "chain_controller", onController),
        ChainAction("Camera", Icons.Default.Videocam, "chain_camera", onCamera),
        ChainAction("Delete", Icons.Default.Delete, "chain_delete", onDelete)
    )
    // Chain tiles are fixed-width (62 dp), so the split law runs on widths.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val visibleCount = ToolRowSplit.visibleCount(
        widthsDp = List(actions.size) { CHAIN_TILE_W_DP },
        availableDp = ToolRowSplit.availableDp(screenWidthDp, contentPaddingDp = CHAIN_CONTENT_PADDING_DP),
        gapDp = CHAIN_GAP_DP
    )
    val visibleActions = actions.take(visibleCount)
    val overflowActions = actions.drop(visibleCount)

    // Publish to the tools grid (build 202): a chain action's test tag
    // doubles as its tool id in the grid section. Build 210: while the
    // unified panel is open the FULL action list is published.
    PublishClipOverflow(
        morePanel = morePanel,
        section = when {
            toolsInGrid && actions.isNotEmpty() -> ClipOverflowSection(
                key = actions.joinToString(",") { it.testTag },
                tools = actions.map { ClipToolItem(it.testTag, it.label, it.icon) },
                usedTools = emptySet(),
                tileTag = { it },
                onTool = { item -> actions.firstOrNull { it.testTag == item.id }?.onClick() }
            )
            overflowActions.isEmpty() -> null
            else -> ClipOverflowSection(
                key = actions.joinToString(",") { it.testTag },
                tools = overflowActions.map { ClipToolItem(it.testTag, it.label, it.icon) },
                usedTools = emptySet(),
                tileTag = { it },
                onTool = { item -> overflowActions.firstOrNull { it.testTag == item.id }?.onClick() }
            )
        }
    )

    // Build 214 (THE DOCK): this panel IS the dock's primary toolbar
    // while a Chain is selected — it stays visible while the panel stage
    // is open above it (the stage carries the full action list; the
    // publish above still runs). Build 211's render-nothing state is
    // superseded by the dock architecture.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 3.dp)
            .background(AmBarSurface, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .padding(vertical = 4.dp)
            .testTag("chain_add_panel"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(start = 6.dp, end = 4.dp)
                .size(36.dp)
                .bouncyClickable(pressedScale = 0.88f, onClick = onBack)
                .testTag("chain_back_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Exit chain", tint = IconActive, modifier = Modifier.size(19.dp))
        }
        Box(Modifier.width(1.dp).height(24.dp).background(com.example.ui.theme.AmHairline))
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = CHAIN_CONTENT_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(CHAIN_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            visibleActions.forEach { action ->
                ChainToolItem(action.label, action.icon, action.testTag, action.onClick)
            }
        }
    }
}

/** One action of [ChainAddPanel] (build 202: the row and the shared panel's clip section render from one list). */
private data class ChainAction(
    val label: String,
    val icon: ImageVector,
    val testTag: String,
    val onClick: () -> Unit
)

/** Chain tile geometry (fixed width by design — see [ChainToolItem]). */
private const val CHAIN_TILE_W_DP = 50
private const val CHAIN_GAP_DP = 5
private const val CHAIN_CONTENT_PADDING_DP = 6

/** A tool of [ChainAddPanel]: fixed width. Clean, consistent with main toolbar. */
@Composable
private fun ChainToolItem(label: String, icon: ImageVector, testTag: String, onClick: () -> Unit) {
    val destructive = label == "Delete"
    Box(
        modifier = Modifier
            .width(CHAIN_TILE_W_DP.dp)
            .fillMaxHeight()
            .bouncyClickable(pressedScale = 0.94f, hoverScale = 1.02f, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AmSpace.xxs, vertical = AmSpace.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (destructive) AmDanger else IconActive,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.height(AmSpace.xxs))
            Text(
                label,
                color = if (destructive) AmDanger else AmTextDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
