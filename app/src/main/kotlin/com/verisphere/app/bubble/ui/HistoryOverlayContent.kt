package com.verisphere.app.bubble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.verisphere.app.R
import com.verisphere.app.bubble.HistoryOverlayMode
import com.verisphere.app.gemini.SourceCitation
import com.verisphere.app.storage.SessionRecord
import com.verisphere.app.ui.detail.DetailPanelContent
import com.verisphere.app.ui.history.HistoryItemRow
import kotlin.math.roundToInt

/**
 * Chat-heads-style overlay (Hernat 2026-05-18) — full-screen scrim with a
 * bottom-anchored Surface card hosting either the chronological history
 * list ([HistoryOverlayMode.List]) or the [DetailPanelContent] for a
 * single record ([HistoryOverlayMode.Detail]).
 *
 * Mounted by `BubbleOverlayService.attachHistoryOverlay()` in a separate
 * focusable WindowManager window so:
 *  - back-button is intercepted by the service's key listener
 *    (`view.setOnKeyListener`) and routed to [onBack];
 *  - tap on the scrim background fires [onDismiss];
 *  - vertical drag on the Surface card > [SWIPE_DISMISS_THRESHOLD_DP] dp
 *    fires [onDismiss] (Material-style swipe-down).
 *
 * Stateless consumer — every interaction surfaces through a callback.
 * The host service owns [HistoryOverlayMode] in a `MutableStateFlow` and
 * mutates it in response to these callbacks (List ↔ Detail ↔ Hidden).
 */
@Composable
fun HistoryOverlayContent(
    mode: HistoryOverlayMode,
    records: List<SessionRecord>,
    onItemClick: (String) -> Unit,
    onBackToList: () -> Unit,
    onSourceClick: (SourceCitation) -> Unit,
    onDismiss: () -> Unit,
) {
    if (mode is HistoryOverlayMode.Hidden) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TAG_HISTORY_OVERLAY_ROOT)
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        var dragOffsetY by remember { mutableFloatStateOf(0f) }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(CARD_HEIGHT_FRACTION)
                .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                .clip(RoundedCornerShape(topStart = CARD_CORNER_DP.dp, topEnd = CARD_CORNER_DP.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .pointerInput(Unit) {
                    val thresholdPx = SWIPE_DISMISS_THRESHOLD_DP.dp.toPx()
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragOffsetY > thresholdPx) {
                                onDismiss()
                            }
                            dragOffsetY = 0f
                        },
                        onDragCancel = { dragOffsetY = 0f },
                    ) { _, dragAmount ->
                        if (dragAmount > 0f || dragOffsetY > 0f) {
                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                        }
                    }
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                DragHandle()
                when (mode) {
                    HistoryOverlayMode.List -> ListContent(
                        records = records,
                        onItemClick = onItemClick,
                        onDismiss = onDismiss,
                    )
                    is HistoryOverlayMode.Detail -> {
                        val record = records.firstOrNull { it.id == mode.recordId }
                        DetailContent(
                            record = record,
                            onBackToList = onBackToList,
                            onSourceClick = onSourceClick,
                            onDismiss = onDismiss,
                        )
                    }
                    HistoryOverlayMode.Hidden -> Unit
                }
            }
        }
    }
}

@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(DRAG_HANDLE_WIDTH_DP.dp)
                .height(DRAG_HANDLE_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun ColumnScope.ListContent(
    records: List<SessionRecord>,
    onItemClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    HeaderRow(
        title = stringResource(R.string.history_overlay_title),
        showBack = false,
        onBack = onDismiss,
        onDismiss = onDismiss,
    )
    if (records.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.history_overlay_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        // Sort newest-first per HistoryViewModel precedent (Hernat 2026-05-18 —
        // the most recent verification surfaces at the top of the list).
        val sortedRecords = remember(records) {
            records.sortedByDescending { it.timestampMs }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_HISTORY_OVERLAY_LIST),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(items = sortedRecords, key = { it.id }) { record ->
                HistoryItemRow(
                    record = record,
                    onClick = { onItemClick(record.id) },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.DetailContent(
    record: SessionRecord?,
    onBackToList: () -> Unit,
    onSourceClick: (SourceCitation) -> Unit,
    onDismiss: () -> Unit,
) {
    HeaderRow(
        title = stringResource(R.string.history_overlay_detail_title),
        showBack = true,
        onBack = onBackToList,
        onDismiss = onDismiss,
    )
    if (record == null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.flash_not_found_headline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
                .testTag(TAG_HISTORY_OVERLAY_DETAIL),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DetailPanelContent(record = record, onSourceClick = onSourceClick)
        }
    }
}

@Composable
private fun HeaderRow(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag(TAG_HISTORY_OVERLAY_BACK),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.history_overlay_back_action),
                )
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
        TitleSlot(title = title)
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(TAG_HISTORY_OVERLAY_CLOSE),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.history_overlay_close_action),
            )
        }
    }
}

@Composable
private fun RowScope.TitleSlot(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
    )
}

private const val SCRIM_ALPHA = 0.45f
private const val CARD_HEIGHT_FRACTION = 0.85f
private const val CARD_CORNER_DP = 20f
private const val DRAG_HANDLE_WIDTH_DP = 40f
private const val DRAG_HANDLE_HEIGHT_DP = 4f
private const val SWIPE_DISMISS_THRESHOLD_DP = 80f

internal const val TAG_HISTORY_OVERLAY_ROOT = "vs_history_overlay_root"
internal const val TAG_HISTORY_OVERLAY_LIST = "vs_history_overlay_list"
internal const val TAG_HISTORY_OVERLAY_DETAIL = "vs_history_overlay_detail"
internal const val TAG_HISTORY_OVERLAY_BACK = "vs_history_overlay_back"
internal const val TAG_HISTORY_OVERLAY_CLOSE = "vs_history_overlay_close"
