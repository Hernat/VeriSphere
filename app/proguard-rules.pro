# ──────────────────────────────────────────────────────────────────────
# VeriSphere — release-only ProGuard / R8 keep + strip rules.
# Architecture reference: D5.5 (size optimisation, not security obfuscation).
# ──────────────────────────────────────────────────────────────────────

# Strip diagnostic logging in release builds (NFR7 — no remote logging,
# no leaked OCR / API key / prompt / session content via shipped logs).
# This strips Log.d, Log.v, AND Log.i (architecture decision D5.5).
# Log.w (warnings) and Log.e (errors) survive R8 minification and are
# the only severity levels available in release; they are local-only
# (Logcat on the user's device) and never leave the device.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ──────────────────────────────────────────────────────────────────────
# kotlinx.serialization keep rules (D3.2).
# Reflection-based serializer discovery must survive R8 minification.
# ──────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep generated $$serializer companions for any @Serializable type.
# The wildcard com.verisphere.app.** is preserved here pending Stories
# 1.10 + 6.1 (HistoryRepository, VersionChecker) which will introduce
# additional @Serializable types in storage/ and update/. Story 1.9's
# narrowed rules below provide explicit coverage for the gemini/ +
# storage/SessionRecord types we ship today (closing the Story 1.1
# deferred-work item "Narrow ProGuard -keep rules to packages with
# @Serializable types").
-keep,includedescriptorclasses class com.verisphere.app.**$$serializer { *; }

# Keep companions that expose serializer() factories.
-keepclassmembers class com.verisphere.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.verisphere.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ──────────────────────────────────────────────────────────────────────
# Story 1.9 — narrowed @Serializable keep rules for gemini/ + SessionRecord.
# Story 1.10 + 6.1 may extend these to storage/HistoryRepository's
# persisted shape and update/VersionInfo respectively. The wildcard
# rule above remains the safety net until those stories settle.
#
# Code-review patch P2 — `gemini.**` (double-star) covers nested
# @Serializable types (e.g. `GeminiClient$GenerateContentResponse$Candidate$Content$Part`).
# A single-star `gemini.*` would only match top-level classes, leaving
# the nested envelope types un-kept and breaking R8 release builds.
# ──────────────────────────────────────────────────────────────────────
-keep,allowobfuscation,allowshrinking class com.verisphere.app.gemini.** { *; }
-keep class com.verisphere.app.gemini.**$Companion { *; }
-keepclassmembers class com.verisphere.app.gemini.** {
    public static **$Companion Companion;
}
-keep,allowobfuscation,allowshrinking class com.verisphere.app.storage.SessionRecord { *; }
-keepclassmembers class com.verisphere.app.storage.SessionRecord {
    public static **$Companion Companion;
}

# ──────────────────────────────────────────────────────────────────────
# OkHttp (4.12) and AndroidX Security (1.1.x) ship their own
# consumer-rules.pro inside the AAR — auto-merged by R8. No additions
# needed unless internal APIs are used (none are in V1).
#
# Compose keep rules are auto-handled by the kotlin.compose plugin.
# ──────────────────────────────────────────────────────────────────────

# AndroidX Security 1.1.0 GA pulls in com.google.crypto.tink (the
# replacement library Google now points the security-crypto API towards).
# Tink references com.google.errorprone.annotations.* as compile-time
# annotations only; they are not packaged at runtime, and AndroidX
# Security's consumer-rules.pro does not include the dontwarn for them.
# Without this rule, :app:minifyReleaseWithR8 fails with "Missing class
# com.google.errorprone.annotations.*". Added in Story 1.4.
-dontwarn com.google.errorprone.annotations.**
