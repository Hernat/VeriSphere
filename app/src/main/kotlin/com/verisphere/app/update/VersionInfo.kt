package com.verisphere.app.update

import kotlinx.serialization.Serializable

/**
 * Schema of `version-info.json` published at
 * `raw.githubusercontent.com/Hernat/VeriSphere/main/version-info.json`
 * (architecture D3.9 / FR22; RELEASING.md → version-info.json format).
 *
 * `latestVersion` and `downloadUrl` are non-nullable with no defaults
 * — a missing field in the JSON triggers `SerializationException`
 * which the caller ([VersionChecker.checkForUpdates]) treats as a
 * silent failure per Story 6.1 AC #7 (returns `null`, no state
 * mutation). These two fields are load-bearing for the banner
 * (Stories 6.2 / 6.3) and a publisher MUST set them correctly.
 *
 * Code-review patch P8 (D2 decision) — `releasedAt` carries a default
 * of `""`. A publisher who forgets the field would otherwise silently
 * break update notification for every user (`SerializationException`
 * → no banner ever). The field is NOT persisted in V1 (Story 6.1
 * only writes `latestVersion` + `downloadUrl` per AC #4); a future
 * "released N days ago" UX (Story 7.x) MUST validate non-empty
 * before formatting.
 *
 * `releasedAt` is ISO-8601 `YYYY-MM-DD` when present (architecture
 * L451). Validation of the date format is deferred to the future
 * consumer (V2 polish bundle).
 */
@Serializable
data class VersionInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val releasedAt: String = "",
)
