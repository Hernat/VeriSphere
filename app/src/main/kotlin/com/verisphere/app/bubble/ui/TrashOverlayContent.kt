package com.verisphere.app.bubble.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Messenger-style "drop to close" affordance (Hernat 2026-05-18). A
 * trash glyph anchored bottom-centre that the user drags the bubble
 * onto to dismiss it. The bubble's host service stops on drop; the
 * user re-summons the bubble by re-opening the VeriSphere main app
 * (`MainActivity.onResume` → `startForegroundService`).
 *
 * Pure stateless presentation — the host service owns the
 * [isBubbleOverTrash] flag (a `StateFlow<Boolean>`) and feeds it as
 * the [highlighted] parameter; this composable just animates the
 * scale + colour transition.
 */
@Composable
fun TrashOverlayContent(highlighted: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (highlighted) HIGHLIGHTED_SCALE else REST_SCALE,
        animationSpec = tween(durationMillis = SCALE_TWEEN_MS),
        label = "trashScale",
    )
    val backgroundColor = if (highlighted) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        Color.Black.copy(alpha = 0.55f)
    }
    val iconTint = if (highlighted) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        Color.White
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TAG_TRASH_OVERLAY_ROOT),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = BOTTOM_PADDING_DP.dp)
                .size(CIRCLE_DIAMETER_DP.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(backgroundColor)
                .testTag(TAG_TRASH_OVERLAY_CIRCLE),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(ICON_DIAMETER_DP.dp),
            )
        }
    }
}

private const val REST_SCALE = 1f
private const val HIGHLIGHTED_SCALE = 1.25f
private const val SCALE_TWEEN_MS = 120
internal const val BOTTOM_PADDING_DP = 56
internal const val CIRCLE_DIAMETER_DP = 64
internal const val ICON_DIAMETER_DP = 32

internal const val TAG_TRASH_OVERLAY_ROOT = "vs_trash_overlay_root"
internal const val TAG_TRASH_OVERLAY_CIRCLE = "vs_trash_overlay_circle"
