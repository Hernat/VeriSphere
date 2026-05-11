@file:Suppress("DEPRECATION")
// `androidx.security:security-crypto` 1.1.0 GA was published with the entire
// API surface marked @Deprecated; Google's stated direction is migration to
// direct Android Keystore + Tink in V2+. For VeriSphere V1 the API remains
// supported and is the architecture-mandated primitive (D1.1, D1.6, AR12).
// The suppression is file-scoped because the deprecated symbols appear in
// imports as well as call sites. V2 migration is tracked in deferred-work.

package com.verisphere.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.verisphere.app.util.redact
import com.verisphere.app.util.tag
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Single-talker primitive over `EncryptedSharedPreferences` (D1.1, D1.6, AR12).
 *
 * Architecture:
 *   - Persistence boundary (architecture lines 758–760): this is the ONLY
 *     class anywhere in `app/` that imports `EncryptedSharedPreferences`
 *     or `MasterKey`. Every other consumer goes through this surface
 *     (directly or via `HistoryRepository` / `RateLimitRepository`).
 *   - Master key: AES-256-GCM, non-extractable, backed by Android Keystore
 *     (D1.6). Created lazily on first access — keeps `Application.onCreate`
 *     free of Keystore I/O (NFR3, cold-start < 1 s). First touch can stall
 *     for ~50–200 ms (Keystore init); reads/writes are synchronous on the
 *     caller's thread (V1 deliberately avoids a `suspend` API per spec
 *     Pitfall #3 — repository layer wraps in `Dispatchers.IO` if needed).
 *   - File: a single shared `vs_secure_prefs` for every persisted V1 value
 *     (D1.4) — bubble position, rate-limit counters, banner-dismiss flag,
 *     tutorial-seen flag, history list, etc. Cross-feature key collisions
 *     fail soft (return null / default) rather than crash, courtesy of the
 *     `ClassCastException` catch on every read path.
 *   - JSON: kotlinx.serialization (D3.2). `ignoreUnknownKeys = true` is a
 *     forward-compat hedge so V1.x reads of older records don't crash
 *     if a field is added in a later release.
 *   - Decode failures fail soft: `readJson` returns `null` on absent /
 *     malformed / type-collided keys; never throws to the caller. The
 *     redacted error is logged via `Log.e` (D2.7 — survives R8, never
 *     leaves the device per NFR7).
 *
 * Boundary discipline (Pitfall #9): every field is `private`. The inline
 * reified `readJson` / `writeJson` route through `@PublishedApi internal`
 * helpers that take an explicit `KSerializer`, so callers can never
 * reach `prefs`, `masterKey`, or `json` directly even from inside the
 * module.
 *
 * Failure recovery (Keystore key invalidation, prefs file corruption) is
 * deferred to V2 hardening — see deferred-work.md. V1's founder-only
 * posture + `allowBackup="false"` (D1.5) + non-user-auth-bound master
 * key combine to make the recovery surface acceptably narrow.
 */
class SecureStorage(context: Context) {

    private val context: Context = context.applicationContext

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(this.context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            this.context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val json: Json = Json {
        ignoreUnknownKeys = true
        // coerceInputValues = true: if a stored record has an explicit JSON
        // null for a non-nullable field (e.g. injectionDetected added in
        // Story 3.1), deserialisation coerces null → the field's default
        // rather than throwing SerializationException and dropping the record.
        // Matches GeminiClient's Json config (same tolerance posture).
        coerceInputValues = true
    }

    inline fun <reified T> readJson(key: String): T? = readJsonInternal(key, serializer<T>())

    inline fun <reified T> writeJson(key: String, value: T) {
        writeJsonInternal(key, serializer<T>(), value)
    }

    fun readString(key: String): String? = try {
        prefs.getString(key, null)
    } catch (e: ClassCastException) {
        Log.e(TAG, "type mismatch for key=$key", e)
        null
    }

    fun writeString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun readLong(key: String, default: Long = 0L): Long = try {
        prefs.getLong(key, default)
    } catch (e: ClassCastException) {
        Log.e(TAG, "type mismatch for key=$key", e)
        default
    }

    fun writeLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun readBoolean(key: String, default: Boolean = false): Boolean = try {
        prefs.getBoolean(key, default)
    } catch (e: ClassCastException) {
        Log.e(TAG, "type mismatch for key=$key", e)
        default
    }

    fun writeBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    @PublishedApi
    internal fun <T> readJsonInternal(key: String, deserializer: DeserializationStrategy<T>): T? {
        val raw = try {
            prefs.getString(key, null)
        } catch (e: ClassCastException) {
            Log.e(TAG, "type mismatch for key=$key", e)
            return null
        } ?: return null
        return try {
            json.decodeFromString(deserializer, raw)
        } catch (e: SerializationException) {
            Log.e(TAG, "decode failed for key=$key (${redact(raw)})", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "decode failed for key=$key (${redact(raw)})", e)
            null
        }
    }

    @PublishedApi
    internal fun <T> writeJsonInternal(
        key: String,
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        prefs.edit().putString(key, json.encodeToString(serializer, value)).apply()
    }

    private companion object {
        val TAG: String = tag("SecureStorage")
        const val FILE_NAME: String = "vs_secure_prefs"
    }
}
