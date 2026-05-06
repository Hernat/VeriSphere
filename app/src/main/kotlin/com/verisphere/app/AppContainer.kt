package com.verisphere.app

import android.content.Context
import com.verisphere.app.storage.RateLimitRepository
import com.verisphere.app.storage.RateLimitRepositoryImpl
import com.verisphere.app.storage.SecureStorage

/**
 * Single dependency-injection graph for the whole application.
 *
 * Architecture: D4.1 — manual constructor injection via a service-locator.
 * No Hilt, no Koin in V1. The trade-off is recorded in the architecture
 * document under "Decision Priority Analysis" — frameworks earn their
 * keep at scale, not at the size of a wrapper app with ~10 collaborators.
 *
 * Subsequent stories populate the lazy fields below. Story 1.1 ships the
 * empty container; the placeholders document where each collaborator
 * will land. When you add a new field, follow the rule:
 *
 *   - the field is `val`, lazy-initialised,
 *   - dependencies are passed by interface (not concrete impl),
 *   - the only `Context` dependency is `applicationContext`.
 *
 * Replacement / migration paths are mechanical because every consumer
 * receives an interface (HistoryRepository, RateLimitRepository, etc.)
 * — never the concrete `*Impl`.
 */
class AppContainer(private val applicationContext: Context) {

    val secureStorage: SecureStorage by lazy {
        SecureStorage(applicationContext)
    }

    val rateLimitRepository: RateLimitRepository by lazy {
        RateLimitRepositoryImpl(
            readLong = secureStorage::readLong,
            writeLong = secureStorage::writeLong,
        )
    }

    // TODO Story 1.9: val httpClient: OkHttpClient by lazy { ... }
    // TODO Story 1.9: val geminiClient: GeminiClient by lazy {
    //     GeminiClient(httpClient, applicationContext, rateLimitRepository)
    // }

    // TODO Story 1.10: val historyRepository: HistoryRepository by lazy {
    //     HistoryRepositoryImpl(secureStorage)
    // }

    // TODO Story 6.1: val versionChecker: VersionChecker by lazy {
    //     VersionChecker(httpClient)
    // }

    // BubbleStateMachine and CapturePipeline live inside BubbleOverlayService
    // (Stories 1.6, 1.8) — owned by the service, not by AppContainer.
}
