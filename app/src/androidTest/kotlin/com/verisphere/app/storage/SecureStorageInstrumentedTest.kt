package com.verisphere.app.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented contract test for [SecureStorage].
 *
 * Runs on a real Android device / emulator because `EncryptedSharedPreferences`
 * needs the Android Keystore service. Black-box only — exercises the public
 * API; never reads the on-disk encrypted file.
 *
 * Test method naming: the architecture (line 426) prescribes backtick-named
 * English-sentence assertions. DEX format prior to version 040 (which would
 * require `minSdk >= 30`; we are at `minSdk = 26` per D5.1) does NOT allow
 * spaces or `@` in method `SimpleName`s — D8 fails the dex step otherwise.
 * Compromise: keep the backtick-quoted English-sentence spirit but use `_`
 * instead of space and drop `@`. Equivalent readability; honours both the
 * naming convention and the DEX constraint. Tracked under deferred-work
 * for V2 if `minSdk` ever rises to 30+.
 */
@RunWith(AndroidJUnit4::class)
class SecureStorageInstrumentedTest {

    private lateinit var storage: SecureStorage

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        storage = SecureStorage(context)
        for (key in TEST_KEYS) {
            storage.clear(key)
        }
    }

    @Test
    fun `JSON_round_trip_restores_a_non_trivial_Serializable_shape`() {
        val original = TestShape(id = "abc", count = 42, tags = listOf("x", "y"))
        storage.writeJson(KEY_JSON, original)

        val restored = storage.readJson<TestShape>(KEY_JSON)

        assertEquals(original, restored)
    }

    @Test
    fun `String_round_trip_restores_the_exact_value`() {
        storage.writeString(KEY_STRING, "hello vs")
        assertEquals("hello vs", storage.readString(KEY_STRING))
    }

    @Test
    fun `Long_round_trip_restores_the_exact_value`() {
        storage.writeLong(KEY_LONG, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, storage.readLong(KEY_LONG))
    }

    @Test
    fun `Boolean_round_trip_restores_the_exact_value`() {
        storage.writeBoolean(KEY_BOOL_TRUE, true)
        storage.writeBoolean(KEY_BOOL_FALSE, false)

        assertTrue(storage.readBoolean(KEY_BOOL_TRUE))
        assertFalse(storage.readBoolean(KEY_BOOL_FALSE))
    }

    @Test
    fun `readJson_returns_null_for_an_absent_key`() {
        assertNull(storage.readJson<TestShape>(KEY_ABSENT))
    }

    @Test
    fun `readString_returns_null_for_an_absent_key`() {
        assertNull(storage.readString(KEY_ABSENT))
    }

    @Test
    fun `readLong_returns_the_supplied_default_for_an_absent_key`() {
        assertEquals(7L, storage.readLong(KEY_ABSENT, default = 7L))
    }

    @Test
    fun `readBoolean_returns_the_supplied_default_for_an_absent_key`() {
        assertTrue(storage.readBoolean(KEY_ABSENT, default = true))
    }

    @Test
    fun `readJson_returns_null_for_a_malformed_value`() {
        storage.writeString(KEY_MALFORMED, "{ not valid json")

        val result = storage.readJson<TestShape>(KEY_MALFORMED)

        assertNull(result)
    }

    @Test
    fun `clear_removes_the_key`() {
        storage.writeString(KEY_TO_CLEAR, "ephemeral")
        storage.clear(KEY_TO_CLEAR)

        assertNull(storage.readString(KEY_TO_CLEAR))
    }

    @Serializable
    private data class TestShape(
        val id: String,
        val count: Int,
        val tags: List<String>,
    )

    private companion object {
        const val KEY_JSON = "test_json_round_trip"
        const val KEY_STRING = "test_string_round_trip"
        const val KEY_LONG = "test_long_round_trip"
        const val KEY_BOOL_TRUE = "test_bool_true"
        const val KEY_BOOL_FALSE = "test_bool_false"
        const val KEY_ABSENT = "test_absent_key"
        const val KEY_MALFORMED = "test_malformed_value"
        const val KEY_TO_CLEAR = "test_to_clear"

        val TEST_KEYS = listOf(
            KEY_JSON,
            KEY_STRING,
            KEY_LONG,
            KEY_BOOL_TRUE,
            KEY_BOOL_FALSE,
            KEY_ABSENT,
            KEY_MALFORMED,
            KEY_TO_CLEAR,
        )
    }
}
