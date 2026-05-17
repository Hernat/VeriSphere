import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
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
        // Story 1.8.5 — Sprint Change 2026-05-07: bumped from 26 to 30
        // because AccessibilityService.takeScreenshot() (architecture
        // D5.13) requires API 30 (Android 11). ~10-15 % of Android
        // devices on 8/9/10 are deferred to V2 if user demand surfaces
        // (deferred-work.md tracks).
        minSdk = 30
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Story 1.9 reads this from BuildConfig.GEMINI_API_KEY.
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKeyForBuildConfig\"")

        // Story 2.2 — toggles the AnchoredDetailPanel between the
        // edge-anchored override (false) and the stock M3 ModalBottomSheet
        // fallback (true). V1 default = true (UX spec line 719 Phase 1
        // acceptance). Flip to false once edge-anchored is validated on
        // device (architect's call). Per-build-type override is unnecessary;
        // both debug and release inherit the defaultConfig value.
        buildConfigField("boolean", "USE_STANDARD_BOTTOM_SHEET", "true")
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

            // Story 7.2 — Architecture D5.6 + epics 7.2 AC #4: universal RELEASE
            // APK targets arm64-v8a + armeabi-v7a only; x86 / x86_64 / riscv64
            // excluded. Scoped to `release` (NOT `defaultConfig`) so debug
            // installDebug on x86_64 AVDs / Chromebook ARC++ keeps working
            // for contributor workflows — Story 7.2 review F4 fix.
            //
            // Empirically NOT a no-op: the pre-Story-7.2 release APK shipped
            // 4 ABI variants of libandroidx.graphics.path.so (transitive
            // Compose path-rendering dep); post-filter only arm64-v8a +
            // armeabi-v7a remain → release APK shrinks ~36 KB.
            //
            // `+=` is additive against this same build type's prior abiFilters
            // value (currently empty). It does NOT protect against a future
            // productFlavor or `android.splits.abi` block declaring its own
            // surface — those operate on different merge axes and can replace
            // this set entirely. If V2 introduces flavors or per-ABI splits,
            // re-audit this block.
            //
            // ChromeOsAbiSupport: intentional spec decision per architecture
            // D5.6 (V1 phone-only; Chromebook ARC++ deferred to V2 per
            // architecture L215-218). The lint warning is a true positive but
            // contradicts the locked V1 posture — suppress at site rather
            // than disable project-wide.
            //noinspection ChromeOsAbiSupport
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
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

    // Story 1.8 — unit tests on the JVM stub `android.jar` throw
    // `RuntimeException` for every Android-platform method (Log.d / w / e
    // included). Returning default values (no-op for void) lets the
    // CapturePipeline's logging calls pass through unit tests without
    // requiring Robolectric. Production code is unchanged.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ─── Detekt (Story 1.3, AR11) ────────────────────────────────────────────
// Architecture-mandated rules: GlobalScope usage, println instead of Log,
// missing @Serializable on repository-consumed types (the third is a custom
// rule scoped to Story 1.10 — placeholder in detekt.yml).
//
// Detekt is wired here but does NOT gate the main `assemble*` tasks. CI
// (main.yml) runs `:app:detekt` as a separate step and hard-fails on any
// violation. Locally, run `./gradlew :app:detekt` on demand.
detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    parallel = true
    buildUponDefaultConfig = true
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
    // Story 6.2 — Icons.Outlined.Info + Icons.Outlined.Close for UpdateBanner.
    implementation(libs.androidx.compose.material.icons.extended)

    // ─── Encrypted local storage (D1.6, NFR6) ───────────────────────────
    implementation(libs.androidx.security.crypto)

    // ─── HTTP + JSON for the Gemini call (D3.1, D3.2) ───────────────────
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // ─── Tests ──────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    // Story 1.9 — MockWebServer drives GeminiClientTest's HTTP-path scenarios.
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
