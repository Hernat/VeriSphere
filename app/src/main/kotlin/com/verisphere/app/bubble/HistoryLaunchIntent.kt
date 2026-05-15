package com.verisphere.app.bubble

import android.content.Context
import android.content.Intent
import com.verisphere.app.MainActivity
import com.verisphere.app.ui.history.HistoryScreenIntent

/**
 * Story 4.3 — Pure factory for the history-open [Intent].
 *
 * Mirrors the Story 2.4 [buildDetailPanelIntent] file-level pattern: kept
 * out of [BubbleOverlayService] so the JVM / androidTest surface can call
 * it without instantiating the `Service`.
 *
 * **Flags rationale**:
 *  - `FLAG_ACTIVITY_NEW_TASK` — required because the caller is a
 *    [android.app.Service] (no Activity stack to launch into); same
 *    rationale as the Story 1.8.5 [MainActivity.ACTION_REQUEST_ACCESSIBILITY]
 *    routing and the Story 2.4 detail-panel intent.
 *  - `FLAG_ACTIVITY_SINGLE_TOP` — combines with the manifest's
 *    `launchMode="singleTop"` to route into [MainActivity.onNewIntent]
 *    when the activity is already in the task (the typical case
 *    post-onboarding). **DO NOT** use `FLAG_ACTIVITY_CLEAR_TOP` — would
 *    re-create the activity instance, throwing away the current
 *    `HistoryViewModel.uiState` cache and triggering a fresh Keystore
 *    Loading window.
 *
 * **Action rationale** — [HistoryScreenIntent.ACTION_OPEN] is the
 * dedicated history-open action, distinct from Story 2.4's
 * `Intent.ACTION_VIEW` + `EXTRA_SESSION_ID` shape (which the existing
 * `MainActivity.resolvePendingDetailSession` filters on by action). The
 * two routes coexist cleanly in `onNewIntent` — one filters by
 * `ACTION_VIEW`, the other by `ACTION_OPEN`.
 *
 * **No extras** — the action by itself is the full payload (no
 * `EXTRA_SESSION_ID` / `EXTRA_BUBBLE_ANCHOR_X_PX`); history-open does
 * not need a record id.
 *
 * @param context Source context for the explicit [MainActivity] component.
 */
internal fun buildOpenHistoryIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(HistoryScreenIntent.ACTION_OPEN)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
