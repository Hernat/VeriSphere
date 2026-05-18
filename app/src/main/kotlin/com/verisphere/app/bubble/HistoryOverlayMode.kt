package com.verisphere.app.bubble

/**
 * Visibility mode of the in-overlay history panel that the bubble service
 * mounts on a tap to the Idle-state bubble. Mirrors Messenger's chat-heads
 * UX (Hernat 2026-05-18): tap the bubble → the history list slides up from
 * the bottom; tap an item → swap the same window to a detail view; back /
 * outside-tap / swipe-down dismisses.
 *
 *  - [Hidden] — no overlay attached; bubble + tooltip behave as before.
 *  - [List] — the scrollable list of [com.verisphere.app.storage.SessionRecord]s
 *    is showing. Tapping an item transitions to [Detail].
 *  - [Detail] — the [com.verisphere.app.ui.detail.DetailPanelContent] for a
 *    single record. Back-arrow returns to [List]; X / outside / swipe-down
 *    dismisses the entire overlay.
 */
sealed interface HistoryOverlayMode {
    data object Hidden : HistoryOverlayMode
    data object List : HistoryOverlayMode
    data class Detail(val recordId: String) : HistoryOverlayMode
}
