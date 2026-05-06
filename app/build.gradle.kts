import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ─── version.properties (single source of truth, D5.11) ──────────────────
// Hardened parsing: file must exist, every part must be present, numeric,
// and inside its versionCode budget. The budget enforces:
//   versionCode = MAJOR * 10_000 + MINOR * 100 + PATCH
// so MINOR / PATCH must stay in 0..99 to avoid versionCode collisions
// (e.g. 0.100.0 would collide with 1.0.0). MAJOR caps at 200 to leave
// integer headroom (Int max ≈ 214 * 10_000_000).
val versionPropsFile = rootProject.file("version.properties")
require(versionPropsFile.exists()) {
    "version.properties is missing at the project root. See architecture D5.11."
}
val versionProps = FileInputStream(versionPropsFile).use { stream ->
    Properties().apply { load(stream) }
}

fun parseVersionPart(key: String, range: IntRange): Int {
    val raw = versionProps.getProperty(key)
        ?: throw GradleException("$key missing in version.properties")
    val parsed = raw.trim().toIntOrNull()
        ?: throw GradleException("$key in version.properties is not an integer: '$raw'")
    require(parsed in range) {
        "$key=$parsed out of range $range — see Pre-V1 Release Policy in architecture D5.11"
    }
    return parsed
}

val versionMajor: Int = parseVersionPart("MAJOR", 0..200)
val versionMinor: Int = parseVersionPart("MINOR", 0..99)
val versionPatch: Int = parseVersionPart("PATCH", 0..99)
val resolvedVersionName: String = "$versionMajor.$versionMinor.$versionPatch"
val resolvedVersionCode: Int = versionMajor * 10_000 + versionMinor * 100 + versionPatch

// ─── GEMINI_API_KEY resolution (D2.2 / D2.9) ─────────────────────────────
// Order: local.properties (dev) → GEMINI_API_KEY env var (CI). Fail fast
// if neither source provides a value — no obfuscation, no silent default.
val resolvedGeminiApiKey: String = run {
    val localPropsFile = rootProject.file("local.properties")
    val fromLocalProps: String? = if (localPropsFile.exists()) {
        FileInputStream(localPropsFile).use { stream ->
            Properties().apply { load(stream) }
                .getProperty("GEMINI_API_KEY")
                ?.takeIf { it.isNotBlank() }
        }
    } else null

    val rawKey = fromLocalProps
        ?: System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: throw GradleException(
            "GEMINI_API_KEY missing. Set it in local.properties (e.g. " +
                "GEMINI_API_KEY=AIza...) or export it as an environment " +
                "variable before building. See CONTRIBUTING.md."
        )

    require(rawKey.none { it == '\n' || it == '\r' }) {
        "GEMINI_API_KEY contains a newline character — clean it up in local.properties."
    }
    rawKey
}

// Escape backslash and double-quote so the key survives buildConfigField's
// verbatim Java-source interpolation. Without this, a key containing `"`
// or `\` would produce a Java compile error rather than the friendly
// GradleException above.
val geminiApiKeyForBuildConfig: String = resolvedGeminiApiKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

// ─── SKIP_RATE_LIMIT debug-only flag ─────────────────────────────────────
// Honoured only in debug builds. Set via -PskipRateLimit=true on the
// Gradle command line for testing (`./gradlew :app:assembleDebug
// -PskipRateLimit=true`). Release builds always rate-limit (D3.7).
val skipRateLimitDebug: String = (project.findProperty("skipRateLimit") as? String) ?: "false"

android {
    namespace = "com.verisphere.app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.verisphere.app"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Story 1.9 reads this from BuildConfig.GEMINI_API_KEY.
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKeyForBuildConfig\"")
    }

    buildTypes {
        debug {
            // Story 1.5 honours this flag in RateLimitRepositoryImpl.
            // Set via Gradle property: ./gradlew :app:assembleDebug -PskipRateLimit=true
            buildConfigField("boolean", "SKIP_RATE_LIMIT", skipRateLimitDebug)
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Release builds NEVER bypass rate limiting — the flag is
            // debug-only by architecture decision (D3.7).
            buildConfigField("boolean", "SKIP_RATE_LIMIT", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // ─── AndroidX core ───────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // ─── Compose (BOM-managed) ──────────────────────────────────────────
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ─── Encrypted local storage (D1.6, NFR6) ───────────────────────────
    implementation(libs.androidx.security.crypto)

    // ─── HTTP + JSON for the Gemini call (D3.1, D3.2) ───────────────────
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // ─── Tests ──────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
