package com.verisphere.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Story 6.3 AC #9 — JVM unit tests for the stale-version gate
 * extracted from [AppContainer._updateAvailableVersion] lazy
 * initializer (closes deferred-work D2 from Story 6.2 review).
 *
 * Testing the helper directly avoids the need for an Android
 * Context / Robolectric / mocked AppContainer construction. The gate
 * is a pure function — given a persisted SemVer string and the
 * current `BuildConfig.VERSION_NAME`, returns the persisted value if
 * and only if it is strictly newer than the current.
 *
 * Boundary discipline (Story 6.3 CDN #9): the gate is applied EXACTLY
 * ONCE at the lazy initializer; this test locks the per-input
 * behaviour so any future refactor that re-applies the gate
 * elsewhere (or weakens it) is caught.
 */
class AppContainerUpdateBannerTest {

    @Test
    fun `gatePersistedVersion returns null when persisted is null`() {
        assertNull(gatePersistedVersion(persisted = null, current = "0.1.0"))
    }

    @Test
    fun `gatePersistedVersion returns null when persisted equals current`() {
        // Equal is not strictly newer — gate to null.
        assertNull(gatePersistedVersion(persisted = "0.1.0", current = "0.1.0"))
    }

    @Test
    fun `gatePersistedVersion returns null when persisted is OLDER than current (upgrade path)`() {
        // The D2 scenario: user upgraded from 0.1.0 to 0.2.0 while
        // SecureStorage still holds update_available_version = 0.1.0
        // from the previous session. The gate suppresses the stale
        // flash.
        assertNull(gatePersistedVersion(persisted = "0.1.0", current = "0.2.0"))
    }

    @Test
    fun `gatePersistedVersion returns persisted when strictly newer than current`() {
        assertEquals(
            "1.0.0",
            gatePersistedVersion(persisted = "1.0.0", current = "0.1.0"),
        )
    }

    @Test
    fun `gatePersistedVersion returns null when persisted is malformed SemVer`() {
        assertNull(
            gatePersistedVersion(persisted = "not-a-version", current = "0.1.0"),
        )
    }

    @Test
    fun `gatePersistedVersion returns null when persisted MAJOR exceeds version-properties cap`() {
        // Story 6.1 patch P7 — parseSemVer caps MAJOR at 200, MINOR/PATCH
        // at 99. Out-of-range values are treated as "no update"
        // (same as malformed). Defends against a maliciously-published
        // 999.0.0 that would otherwise pin the banner permanently.
        assertNull(
            gatePersistedVersion(persisted = "999.0.0", current = "0.1.0"),
        )
    }
}
