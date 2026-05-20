package com.verisphere.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.verisphere.app.VeriSphereApplication
import com.verisphere.app.onboarding.OnboardingOrchestrator
import com.verisphere.app.util.GeminiKeyValidation
import com.verisphere.app.util.SerpKeyValidation
import com.verisphere.app.util.normalizeApiKey
import com.verisphere.app.util.tag
import com.verisphere.app.util.validateGeminiKey
import com.verisphere.app.util.validateSerpKey
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Story 10.1 — UI state for the Paramètres tab.
 *
 * One-way data flow : the screen renders this immutable state ; the
 * user drives mutation via the ViewModel actions ; the ViewModel
 * recomputes the state + emits.
 *
 * @property isLoading `true` while the initial SecureStorage read is
 *   in-flight on cold start (mitigates the 50-200 ms Keystore warmup
 *   per NFR3). Drops to `false` once the drafts are seeded.
 * @property geminiKeyDraft Current value of the Gemini key field. Held
 *   in the ViewModel so the user's typing survives configuration changes.
 * @property geminiKeyVisible `false` = password mask, `true` = plain
 *   text. Toggle via [SettingsViewModel.toggleGeminiVisibility].
 * @property geminiValidation Live validation outcome for the current
 *   draft ; `null` until the user attempts a save (we don't show
 *   "invalid format" errors as soon as the user types — only after Save).
 * @property serpKeyDraft Current SerpAPI key field value.
 * @property serpKeyVisible Mask toggle for SerpAPI field.
 * @property serpValidation Same lazy-validation contract as Gemini.
 * @property saveInFlight `true` between the user tap and the
 *   SecureStorage write completion. Disables the Save button to prevent
 *   double-tap races.
 */
data class SettingsState(
    val isLoading: Boolean = true,
    val geminiKeyDraft: String = "",
    val geminiKeyVisible: Boolean = false,
    val geminiValidation: GeminiKeyValidation? = null,
    val serpKeyDraft: String = "",
    val serpKeyVisible: Boolean = false,
    val serpValidation: SerpKeyValidation? = null,
    val saveInFlight: Boolean = false,
    /**
     * Story 10.1 polish 2026-05-20 — last persisted Gemini key
     * (post-init seed + post-onSave success). Used by the Composable
     * to detect "draft equals persisted" and surface the
     * `✓ Sauvegardée` supportingText override instead of the helper
     * link text. Empty string when no key has ever been saved.
     */
    val persistedGeminiKey: String = "",
    /** Same role as [persistedGeminiKey] for the SerpAPI key. */
    val persistedSerpKey: String = "",
)

/**
 * Story 10.1 — one-shot events emitted by the ViewModel after a
 * successful save. The Composable layer collects these via
 * `LaunchedEffect` to drive the SnackbarHost + the `onKeysSaved`
 * MainActivity callback.
 */
sealed class SettingsEvent {
    /** Both keys persisted successfully. */
    data object KeysSaved : SettingsEvent()
}

/**
 * Story 10.1 — ViewModel backing the new SettingsScreen.
 *
 * **Lambda-seam pattern** — constructor takes [OnboardingOrchestrator]
 * (not Context / SecureStorage directly) so JVM unit tests can pass a
 * fake-store orchestrator without booting Android.
 *
 * **Write discipline** — `onSave()` wraps the SecureStorage writes in
 * `viewModelScope.launch { withContext(NonCancellable + Dispatchers.IO) { ... } }`
 * (Story 5.2 P4/P7 + Story 5.3 CDN #5 pattern) because the activity
 * may be destroyed between the user tap and the
 * `prefs.edit().apply()` completion if the user backgrounds the app
 * mid-save.
 *
 * **Lazy validation** — `geminiValidation` / `serpValidation` are
 * `null` until the user attempts a save. We don't show "invalid
 * format" errors as soon as the user types (rude) ; we only surface
 * the validation outcome after the explicit Save action.
 */
class SettingsViewModel(
    private val orchestrator: OnboardingOrchestrator,
    // P7 (2026-05-20 code-review) — injectable dispatcher so JVM tests
    // can pass a `StandardTestDispatcher` + `Dispatchers.setMain` and
    // drive the IO hops via `advanceUntilIdle`. Production defaults to
    // `Dispatchers.IO` (the EncryptedSharedPreferences first-access
    // 50-200 ms warmup stall belongs there).
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    // P2 (2026-05-20 code-review) — `replay = 1` so a fast-collector
    // race (Composable re-subscribes between emit and collect during a
    // configuration change) does not silently lose the KeysSaved
    // event. `extraBufferCapacity = 1` retained for back-to-back rapid
    // saves (defensive — UI disables Save during in-flight write, but
    // belts-and-braces). `BufferOverflow.DROP_OLDEST` so a second
    // emission supersedes the first rather than blocking the emit
    // (we don't care about stale "saved" notifications).
    private val _events = MutableSharedFlow<SettingsEvent>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        // Seed the drafts off-main-thread to absorb the 50-200 ms
        // EncryptedSharedPreferences first-access stall on cold start.
        // P3 — runCatching guards a Keystore decrypt failure (after
        // factory-reset-restore / Tink corruption) ; empty fallback
        // routes the user to the first-launch banner.
        // EH F3 — the `_state.update` preserves user-typed drafts via
        // the `isEmpty()` guard. The UI gates field rendering on
        // `isLoading=true` so this race is unreachable through the
        // Composable surface, but the ViewModel itself stays correct
        // when called from programmatic / test contexts where the user
        // can mutate drafts before init completes.
        viewModelScope.launch {
            val geminiSeed = withContext(ioDispatcher) {
                runCatching { orchestrator.readUserGeminiApiKey().orEmpty() }.getOrDefault("")
            }
            val serpSeed = withContext(ioDispatcher) {
                runCatching { orchestrator.readUserSerpApiKey().orEmpty() }.getOrDefault("")
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    geminiKeyDraft = if (it.geminiKeyDraft.isEmpty()) geminiSeed else it.geminiKeyDraft,
                    serpKeyDraft = if (it.serpKeyDraft.isEmpty()) serpSeed else it.serpKeyDraft,
                    // Mirror the persisted values so the Composable can
                    // detect "draft == persisted" and surface the
                    // ✓ Sauvegardée indicator.
                    persistedGeminiKey = geminiSeed,
                    persistedSerpKey = serpSeed,
                )
            }
        }
    }

    fun onGeminiKeyChange(value: String) {
        _state.update {
            it.copy(
                geminiKeyDraft = value,
                // Drop prior validation as soon as the user edits — re-validate on save.
                geminiValidation = null,
            )
        }
    }

    fun onSerpKeyChange(value: String) {
        _state.update {
            it.copy(
                serpKeyDraft = value,
                serpValidation = null,
            )
        }
    }

    fun toggleGeminiVisibility() {
        _state.update { it.copy(geminiKeyVisible = !it.geminiKeyVisible) }
    }

    fun toggleSerpVisibility() {
        _state.update { it.copy(serpKeyVisible = !it.serpKeyVisible) }
    }

    /**
     * Validate + persist both drafts. If Gemini validation fails, the
     * save is cancelled — the ViewModel surfaces the validation outcome
     * via `state.geminiValidation` for the Composable to render an
     * error message under the field. SerpAPI is always-valid (empty OK).
     */
    fun onSave() {
        val snapshot = _state.value
        val geminiOutcome = validateGeminiKey(snapshot.geminiKeyDraft)
        val serpOutcome = validateSerpKey(snapshot.serpKeyDraft)

        // Hard gate on Gemini : per V0.2.0-beta code-review D5 resolution
        // 2026-05-20, ANY non-Valid outcome (Empty OR InvalidFormat)
        // cancels the save. V1 over-protection vs the spec letter
        // "Empty AND previously-saved key only" — defensible because
        // there is no explicit "Clear key" affordance (D3 deferred W7).
        if (geminiOutcome != GeminiKeyValidation.Valid) {
            _state.update {
                it.copy(
                    geminiValidation = geminiOutcome,
                    serpValidation = serpOutcome,
                    saveInFlight = false,
                )
            }
            return
        }

        _state.update {
            it.copy(
                geminiValidation = geminiOutcome,
                serpValidation = serpOutcome,
                saveInFlight = true,
            )
        }

        // P2 (2026-05-20 code-review) — race cluster fix :
        //  - Re-read the trimmed drafts from `_state.value` INSIDE the
        //    NonCancellable block so a fast-typing user does not see
        //    the stale `snapshot` written. (TOCTOU window between
        //    snapshot capture + the IO write is closed.)
        //  - try / finally around the write so `saveInFlight = false`
        //    always fires even if `writeUserGeminiApiKey` throws (e.g.
        //    Keystore decrypt failure). Without this, an IO exception
        //    would permanently lock the Save button.
        //  - Both the success event + the saveInFlight reset live
        //    INSIDE the NonCancellable scope so a mid-save activity
        //    destruction doesn't drop the snackbar + banner-refresh.
        //  - `MutableSharedFlow(replay = 1)` so a collector that
        //    re-subscribes during a config change still picks up the
        //    most recent event ; see field declaration above for the
        //    full BufferOverflow rationale.
        viewModelScope.launch {
            withContext(NonCancellable + ioDispatcher) {
                try {
                    val freshGemini = normalizeApiKey(_state.value.geminiKeyDraft)
                    val freshSerp = normalizeApiKey(_state.value.serpKeyDraft)
                    orchestrator.writeUserGeminiApiKey(freshGemini)
                    orchestrator.writeUserSerpApiKey(freshSerp)
                    // Mirror the persisted values so the ✓ Sauvegardée
                    // indicator surfaces immediately after a successful
                    // save (without waiting for a re-init / re-read).
                    _state.update {
                        it.copy(
                            persistedGeminiKey = freshGemini,
                            persistedSerpKey = freshSerp,
                        )
                    }
                    _events.tryEmit(SettingsEvent.KeysSaved)
                } catch (t: Throwable) {
                    // P2 (2026-05-20 code-review) — swallow Keystore /
                    // SecureStorage exceptions so they don't propagate
                    // to viewModelScope's CoroutineExceptionHandler
                    // (which crashes the ViewModel + the JVM tests'
                    // runTest reports an UncaughtExceptionsBeforeTest).
                    // Logged at WARN with no sensitive payload (just
                    // the exception class) per the privacy posture
                    // shared with GeminiClient.kt L77-84.
                    Log.w(TAG, "save failed: ${t.javaClass.simpleName}")
                } finally {
                    _state.update { it.copy(saveInFlight = false) }
                }
            }
        }
    }

    companion object {
        private val TAG = tag("SettingsViewModel")

        /**
         * `ViewModelProvider.Factory` defined alongside the ViewModel
         * (mirrors [com.verisphere.app.ui.history.HistoryViewModel.Factory]
         * pattern). Consumes [VeriSphereApplication.container] via
         * `CreationExtras`.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as VeriSphereApplication
                SettingsViewModel(
                    orchestrator = application.container.onboardingOrchestrator,
                )
            }
        }
    }
}
