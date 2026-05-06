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
-keep,includedescriptorclasses class com.verisphere.app.**$$serializer { *; }

# Keep companions that expose serializer() factories.
-keepclassmembers class com.verisphere.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.verisphere.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ──────────────────────────────────────────────────────────────────────
# OkHttp (4.12) and AndroidX Security (1.1.x) ship their own
# consumer-rules.pro inside the AAR — auto-merged by R8. No additions
# needed unless internal APIs are used (none are in V1).
#
# Compose keep rules are auto-handled by the kotlin.compose plugin.
# ──────────────────────────────────────────────────────────────────────
