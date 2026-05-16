package com.verisphere.app

import android.app.Application
import kotlinx.coroutines.launch

/**
 * Application entry point. Holds the single AppContainer service-locator
 * graph (D4.1). Every Activity and Service obtains its collaborators via
 *
 *     (application as VeriSphereApplication).container
 *
 * NEVER via static singletons or `Context.applicationContext as MyApp`.
 *
 * Cold-start budget is < 1 s on a mid-range device (NFR3). Keep
 * onCreate() minimal — only fire-and-forget launches that MUST run at
 * process-launch (Story 6.1 version-info fetch). Heavier work goes
 * through the container's lazy fields and only happens on first access
 * from the relevant call site.
 */
class VeriSphereApplication : Application() {

    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        // Story 6.1 — one-shot version-info fetch on app launch.
        // Fire-and-forget on applicationScope (Dispatchers.IO) so
        // onCreate returns immediately and the NFR3 < 1 s cold-start
        // budget is preserved. The fetch result is discarded by this
        // caller — VersionChecker persists update state via
        // SecureStorage (AC #4 / #5). Failures are silent per AC #7
        // (UX spec L623).
        container.applicationScope.launch {
            container.versionChecker.checkForUpdates()
        }
    }
}
