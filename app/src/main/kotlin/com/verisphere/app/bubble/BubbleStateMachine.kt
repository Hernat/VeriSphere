package com.verisphere.app.bubble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single owner of the bubble's visual [BubbleState] (architecture:
 * AR21, D4.2, D4.3). POKO — no Android type dependencies, runs on the
 * JVM under unit tests.
 *
 * Reducer shape (architecture lines 540–548, "Pattern Examples"):
 *   - [onEvent] is the only mutation entry point.
 *   - State transitions go through `_state.update { current -> reduce(current, event) }`
 *     so the StateFlow contract holds (no `var`, no `_state.value =`).
 *   - [reduce] is a pure `(current, event) -> next` function — no side
 *     effects. Side effects (the timer launch) live in [onEvent] alongside
 *     the update call.
 *
 * Adaptive presence (UX-DR5): the 5 s inactivity timer lives here — not
 * in the composable — because [BubbleOverlay][com.verisphere.app.bubble.ui.BubbleOverlay]
 * is a stateless consumer per the architecture's State-boundary section
 * (lines 762–764). Each [BubbleEvent.UserActivity] cancels the previous
 * timer Job FIRST (per AC #4), then updates state, then launches a new
 * timer. The launch's body re-checks cancellation via [ensureActive]
 * AFTER `delay()` returns — closing the race where a timer's continuation
 * has already been scheduled and a fresh `UserActivity` fires before the
 * scheduled continuation runs (issue: stale `AutoFadeTimeout` would otherwise
 * overwrite a freshly-set `Idle(faded = false)`).
 *
 * The [coroutineScope] parameter has two purposes: production wiring
 * (the service injects `serviceScope` so the timer dies with the service)
 * AND test seam (tests inject a `TestScope` whose virtual clock is driven
 * by `advanceTimeBy`). The service ALWAYS passes its own scope; the
 * `MainScope()` default is for tests / quick callers and is documented
 * as a leaky default in deferred-work.md (V2 will make the parameter
 * required).
 */
class BubbleStateMachine(
    initial: BubbleState = BubbleState.Idle(faded = false),
    private val coroutineScope: CoroutineScope = kotlinx.coroutines.MainScope(),
) {

    private val _state: MutableStateFlow<BubbleState> = MutableStateFlow(initial)

    val state: StateFlow<BubbleState> = _state.asStateFlow()

    private var fadeJob: Job? = null

    fun onEvent(event: BubbleEvent) {
        // Cancel BEFORE updating state (AC #4): prevents a stale timer's
        // already-scheduled continuation from overwriting the fresh state
        // produced by reduce().
        if (event is BubbleEvent.UserActivity) {
            fadeJob?.cancel()
        }
        _state.update { current -> reduce(current, event) }
        if (event is BubbleEvent.UserActivity) {
            fadeJob = coroutineScope.launch {
                delay(FADE_DELAY_MS)
                // ensureActive() throws CancellationException if our Job
                // was cancelled while delay() was suspended OR after
                // delay() resumed but before this check. Without this,
                // a UserActivity arriving in the same dispatcher tick as
                // the timer's expiry would race: the cancelled timer's
                // synchronous body would still fire AutoFadeTimeout and
                // flip state back to faded=true, immediately after the
                // UserActivity set faded=false.
                ensureActive()
                onEvent(BubbleEvent.AutoFadeTimeout)
            }
        }
    }

    /**
     * Cancels any active inactivity timer. The service calls this
     * between `ON_PAUSE` and `ON_STOP` in `onDestroy` so the timer Job
     * is severed before `serviceScope` itself is cancelled. Cancelling
     * `serviceScope` would cancel the timer too, but explicit dispose
     * keeps lifecycle ordering symmetric and survives a future
     * migration off `serviceScope`.
     */
    fun dispose() {
        fadeJob?.cancel()
        fadeJob = null
    }

    /**
     * Pure state transition function. Side-effect-free, exhaustive on
     * [BubbleEvent], usable from `_state.update { reduce(it, event) }`.
     */
    private fun reduce(current: BubbleState, event: BubbleEvent): BubbleState = when (event) {
        BubbleEvent.UserActivity -> BubbleState.Idle(faded = false)
        BubbleEvent.AutoFadeTimeout ->
            // Idempotent on Idle; forward-compat no-op when current is a
            // future non-Idle variant (Stories 1.8+). A stale timeout from
            // a previous Idle period must not flip a Pressing / Verdict /
            // Failure state into faded-Idle.
            (current as? BubbleState.Idle)?.copy(faded = true) ?: current
    }

    companion object {
        const val FADE_DELAY_MS: Long = 5_000L
    }
}
