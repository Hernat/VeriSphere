package com.verisphere.app.ui.shell

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.verisphere.app.R
import com.verisphere.app.ui.theme.VSPalette
import com.verisphere.app.ui.theme.VeriSphereTheme

/**
 * Story 10.1 — bottom-navigation tabs surfaced by [AppShell].
 *
 * Two tabs in V1 :
 *  - [HISTORY] — the steady-state HistoryScreen surface (the pre-Story-10.1
 *    `else ->` branch of MainActivity's cascade).
 *  - [SETTINGS] — the new BYOK API-keys surface (SettingsScreen).
 *
 * Tab ordering is fixed (HISTORY left, SETTINGS right) to mirror the
 * conventional Android navigation pattern.
 */
enum class AppTab { HISTORY, SETTINGS }

/**
 * Story 10.1 — global 2-tab application shell that wraps the
 * post-onboarding steady-state surfaces.
 *
 * **Pre-Story-10.1** : MainActivity's cascade `else ->` mounted
 * `HistoryScreen` directly. **Post-Story-10.1** : that same branch
 * mounts this composable which hosts a Material 3 `NavigationBar` +
 * swaps between the History and Settings content slots.
 *
 * **Hoisted tab state** (P1 post-code-review 2026-05-20) — the
 * `selectedTab` + `onSelectTab` pair is now hoisted to MainActivity
 * (was internal `rememberSaveable` pre-review). Rationale : the pre-
 * review pattern locked `initialTab` on first composition only,
 * which meant a saveable bundle restored after process death held a
 * stale tab choice — a user who had a configured key would still
 * land on Paramètres if they were last viewing it before kill, even
 * when MainActivity now computes `effectiveInitialTab = HISTORY`.
 * Hoisting lets MainActivity own the "manual override vs. derived
 * default" composition (see MainActivity's `manualTabChoice +
 * effectiveInitialTab` cascade).
 *
 * **First-launch soft redirect** (Story 10.1 AC #11) : MainActivity
 * passes `selectedTab = AppTab.SETTINGS` when the user has not yet
 * configured a Gemini API key, surfacing the Paramètres tab + first-
 * launch banner on the first frame. Once the key is saved, future
 * launches default to `AppTab.HISTORY` unless the user has explicitly
 * navigated to Paramètres via a tap (tracked by MainActivity's
 * `manualTabChoice`).
 *
 * **Stateless callbacks pattern** — same posture as
 * [com.verisphere.app.ui.onboarding.OnboardingTutorialOverlay] (Story
 * 5.1 P7) : this composable owns layout + tab-render only ; the
 * content slots + tab state are caller-provided so the shell stays
 * Context-free and unit-testable.
 *
 * **Brand palette** : the NavigationBar uses `VSPalette.paper` as the
 * container colour (slightly lighter than the canvas-ink content
 * area) ; selected items use `accentSageDeep` icon + `ink` text + a
 * `accentSage.copy(alpha = 0.18f)` indicator pill — mirrors the
 * post-2026-05-19 WisprFlow refonte palette shared with
 * AccessibilityExplanationScreen, OnboardingTutorialOverlay, and
 * PermissionExplanationScreen (commit `d91659a` + `53eb558`).
 *
 * @param selectedTab Currently-active tab. Hoisted to MainActivity so
 *   the effective tab can be derived from `manualTabChoice ?:
 *   (userGeminiKey.isBlank()) AppTab.SETTINGS else AppTab.HISTORY`.
 * @param onSelectTab Invoked when the user taps a tab in the
 *   NavigationBar. MainActivity persists the choice in
 *   `manualTabChoice` (a `rememberSaveable<AppTab?>`).
 * @param historyContent Composable slot rendered when [AppTab.HISTORY]
 *   is selected. Production wires this to [com.verisphere.app.ui.history.HistoryScreen].
 * @param settingsContent Composable slot rendered when [AppTab.SETTINGS]
 *   is selected. Production wires this to the new SettingsScreen.
 */
@Composable
fun AppShell(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    historyContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {

    val historyTabLabel = stringResource(R.string.tab_history_label)
    val settingsTabLabel = stringResource(R.string.tab_settings_label)
    val historyTabA11y = stringResource(R.string.tab_history_content_description)
    val settingsTabA11y = stringResource(R.string.tab_settings_content_description)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VSPalette.canvas,
        bottomBar = {
            NavigationBar(
                containerColor = VSPalette.paper,
                modifier = Modifier.testTag(TAG_APP_SHELL_NAV_BAR),
            ) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = VSPalette.accentSageDeep,
                    selectedTextColor = VSPalette.ink,
                    indicatorColor = VSPalette.accentSage.copy(alpha = 0.18f),
                    unselectedIconColor = VSPalette.inkMuted,
                    unselectedTextColor = VSPalette.inkMuted,
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.HISTORY,
                    onClick = { onSelectTab(AppTab.HISTORY) },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                        )
                    },
                    label = { Text(text = historyTabLabel) },
                    colors = colors,
                    modifier = Modifier
                        .testTag(TAG_APP_SHELL_TAB_HISTORY)
                        .semantics { contentDescription = historyTabA11y },
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.SETTINGS,
                    onClick = { onSelectTab(AppTab.SETTINGS) },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                        )
                    },
                    label = { Text(text = settingsTabLabel) },
                    colors = colors,
                    modifier = Modifier
                        .testTag(TAG_APP_SHELL_TAB_SETTINGS)
                        .semantics { contentDescription = settingsTabA11y },
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when (selectedTab) {
                AppTab.HISTORY -> historyContent()
                AppTab.SETTINGS -> settingsContent()
            }
        }
    }
}

internal const val TAG_APP_SHELL_NAV_BAR: String = "vs_app_shell_nav_bar"
internal const val TAG_APP_SHELL_TAB_HISTORY: String = "vs_app_shell_tab_history"
internal const val TAG_APP_SHELL_TAB_SETTINGS: String = "vs_app_shell_tab_settings"

@Preview(showBackground = true, name = "History tab Light")
@Preview(showBackground = true, name = "History tab Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppShellHistoryTabPreview() {
    VeriSphereTheme {
        var tab: AppTab by remember { mutableStateOf(AppTab.HISTORY) }
        AppShell(
            selectedTab = tab,
            onSelectTab = { tab = it },
            historyContent = { Text("History tab placeholder") },
            settingsContent = { Text("Settings tab placeholder") },
        )
    }
}

@Preview(showBackground = true, name = "Settings tab Light")
@Preview(showBackground = true, name = "Settings tab Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppShellSettingsTabPreview() {
    VeriSphereTheme {
        var tab: AppTab by remember { mutableStateOf(AppTab.SETTINGS) }
        AppShell(
            selectedTab = tab,
            onSelectTab = { tab = it },
            historyContent = { Text("History tab placeholder") },
            settingsContent = { Text("Settings tab placeholder") },
        )
    }
}
