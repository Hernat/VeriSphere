package com.verisphere.app.util

/**
 * Centralised log-tag helpers for VeriSphere.
 *
 * Architecture references:
 *   - Step 5 — Logging Pattern: tag format `"VS.<Component>"`.
 *   - D5.5: Log.d / Log.v / Log.i are stripped by R8 in release builds.
 *   - D2.7 / NFR7: Log.e is local-only and never sent off-device.
 *
 * Forbidden in any log message: OCR text, captured frame data, the
 * Gemini API key, the full system prompt, full session content. Use
 * `redact(value)` for any value that approaches these categories.
 */

/**
 * Build a log tag in the canonical `"VS.<Component>"` format.
 *
 *     private val TAG = tag("GeminiClient")  // "VS.GeminiClient"
 *     Log.d(TAG, "Sending verification request")
 */
fun tag(name: String): String = "VS.$name"

/**
 * Replace a sensitive value with a length-only placeholder before
 * logging. Use for any string that approaches OCR text, captured frame
 * data, the Gemini API key, the system prompt, or session content
 * (NFR7 / D2.7).
 *
 *     Log.d(TAG, "Received response of size ${redact(rawJson)}")
 *     // → "Received response of size <redacted 1247 chars>"
 */
fun redact(value: String): String = "<redacted ${value.length} chars>"
