package com.verisphere.app.serp

import android.util.Log
import com.verisphere.app.util.tag
import java.util.concurrent.atomic.AtomicLong

/**
 * Epic 9 Story 9.1 — quota-aware throttle for SerpAPI calls.
 *
 * **Why** : SerpAPI has a monthly quota; once exhausted the API returns
 * HTTP 429 (or a JSON payload with `error` mentioning "quota"). Per Epic
 * 9 plan decision "Verdict Gemini quand même" + "Sticky quota flag",
 * when this fires we bypass SerpAPI entirely for [QUOTA_COOLDOWN_MS] to
 * avoid (a) wasting more requests against an exhausted account, and (b)
 * adding 15 s of latency to every verification while SerpAPI returns 429.
 *
 * **State** : single in-memory `AtomicLong` holding the wall-clock
 * timestamp of the last quota-exceeded signal. Survives across pipeline
 * calls (the gate is a singleton via [com.verisphere.app.AppContainer])
 * but resets on process death — that's intentional: a fresh process is
 * a fresh opportunity to retry, and we don't want to persist a sticky
 * "SerpAPI disabled" state to disk where it could outlive the actual
 * quota reset on SerpAPI's side.
 *
 * **Reset path** : [resetQuotaGate] is called by the pipeline whenever
 * SerpAPI returns [SerpOutcome.Success], which can happen either (a) the
 * cooldown window expired and the next call succeeded, or (b) the user's
 * quota was topped up on SerpAPI's side mid-cooldown.
 *
 * @param nowMillisProvider time source (defaulted to `System.currentTimeMillis`
 *        — overridden in [SerpQuotaGateTest] for deterministic time control).
 */
class SerpQuotaGate(
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val lastQuotaHitMillis = AtomicLong(NEVER)

    /**
     * Returns `true` if SerpAPI should be skipped for the current call.
     * Defensive : if [lastQuotaHitMillis] is in the future (clock skew),
     * treat as "no recent quota hit" and clear the gate so we don't strand
     * the pipeline in a permanent skip state.
     */
    fun shouldSkipSerp(): Boolean {
        val lastHit = lastQuotaHitMillis.get()
        if (lastHit == NEVER) return false
        val now = nowMillisProvider()
        if (lastHit > now) {
            // Clock skew defence (code-review F8 — atomic CAS): only
            // clear if the value we observed is still there. A concurrent
            // markQuotaExceeded() between our get() and set() would
            // otherwise be silently clobbered.
            lastQuotaHitMillis.compareAndSet(lastHit, NEVER)
            return false
        }
        // Code-review F9 — forward clock-skew upper bound. If the user
        // moved their wall clock forward then back, lastHit may be from
        // hours in the past relative to `now` and still LOOK in-window
        // when it shouldn't be. Bound the cooldown effective lifetime
        // at 2 × QUOTA_COOLDOWN_MS — beyond that we treat the mark as
        // stale and clear it (atomic).
        if ((now - lastHit) > 2 * QUOTA_COOLDOWN_MS) {
            lastQuotaHitMillis.compareAndSet(lastHit, NEVER)
            return false
        }
        return (now - lastHit) < QUOTA_COOLDOWN_MS
    }

    /** Call when SerpAPI signals quota exhaustion (HTTP 429 or "quota" error). */
    fun markQuotaExceeded() {
        lastQuotaHitMillis.set(nowMillisProvider())
        Log.w(TAG, "SerpQuotaGate active for ${QUOTA_COOLDOWN_MS / 60_000} min cooldown")
    }

    /** Call when SerpAPI succeeds — clears the cooldown. */
    fun resetQuotaGate() {
        if (lastQuotaHitMillis.getAndSet(NEVER) != NEVER) {
            Log.i(TAG, "SerpQuotaGate cleared after Success")
        }
    }

    private companion object {
        val TAG = tag("SerpQuotaGate")
        const val NEVER: Long = Long.MIN_VALUE

        /** 15 minutes — long enough to ride out short bursts of 429s,
         *  short enough that occasional users don't lose enrichment for
         *  hours after a single transient quota signal. */
        const val QUOTA_COOLDOWN_MS: Long = 15 * 60 * 1000
    }
}
