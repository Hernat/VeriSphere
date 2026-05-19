import java.io.File
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

// ─── SERP_API_KEY resolution (Epic 9 Story 9.1) ──────────────────────────
// Optional secondary fact-check source. Same lookup order as GEMINI_API_KEY
// (local.properties → env var) but DOES NOT fail the build when missing —
// SerpAPI is graceful-degradation per Epic 9 plan: empty key → pipeline
// skips SerpAPI entirely + uses Gemini-only verdict. Contributors without
// a SerpAPI account can build and run the app normally.
val resolvedSerpApiKey: String = run {
    val localPropsFile = rootProject.file("local.properties")
    val fromLocalProps: String? = if (localPropsFile.exists()) {
        FileInputStream(localPropsFile).use { stream ->
            Properties().apply { load(stream) }
                .getProperty("SERP_API_KEY")
                ?.takeIf { it.isNotBlank() }
        }
    } else null

    fromLocalProps
        ?: System.getenv("SERP_API_KEY")?.takeIf { it.isNotBlank() }
        ?: ""
}.also { key ->
    require(key.none { it == '\n' || it == '\r' }) {
        "SERP_API_KEY contains a newline character — clean it up in local.properties."
    }
}

val serpApiKeyForBuildConfig: String = resolvedSerpApiKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

// ─── RELEASE_KEYSTORE_* resolution (D2.10, D5.4, Story 7.3) ──────────────
// CI populates four env vars from GitHub Secrets; `release.yml` decodes the
// base64 keystore secret into `$RUNNER_TEMP/release.keystore` and exports
// the path through `RELEASE_KEYSTORE_PATH`. Locally, contributors can set
// the same four env vars pointing at their own dev keystore.
//
// Resolution rules:
//   - All 4 set + file exists → return KeystoreInfo (signs the release APK).
//   - All 4 unset           → return null (unsigned fallback; preserves the
//                              pre-Story-7.3 local-dev experience + the
//                              pre-keystore CI graceful no-op at
//                              release.yml L52-54).
//   - 1..3 set              → throw GradleException (operator error:
//                              secret typo / partial paste; better to fail
//                              loud than ship a silently-unsigned APK).
//   - All 4 set + file missing → throw GradleException (likely base64
//                              decode failure in release.yml; same trap).
data class KeystoreInfo(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)
val resolvedReleaseKeystore: KeystoreInfo? = run {
    val envPath = System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    val envStorePw = System.getenv("RELEASE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val envAlias = System.getenv("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val envKeyPw = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

    val pairs = listOf(
        "RELEASE_KEYSTORE_PATH" to envPath,
        "RELEASE_KEYSTORE_PASSWORD" to envStorePw,
        "RELEASE_KEY_ALIAS" to envAlias,
        "RELEASE_KEY_PASSWORD" to envKeyPw,
    )
    val setCount = pairs.count { it.second != null }
    when (setCount) {
        0 -> null
        4 -> {
            val storeFile = File(envPath!!)
            require(storeFile.exists() && storeFile.isFile) {
                "RELEASE_KEYSTORE_PATH points at '$envPath' which does not exist " +
                    "or is not a regular file. If you are on CI, check the " +
                    "release.yml `Decode keystore` step — a malformed " +
                    "RELEASE_KEYSTORE_BASE64 secret may have produced an " +
                    "empty file. Locally, set RELEASE_KEYSTORE_PATH to a " +
                    "valid keystore file OR unset all four RELEASE_KEYSTORE_* " +
                    "env vars to fall back to an unsigned APK."
            }
            KeystoreInfo(
                storeFile = storeFile,
                storePassword = envStorePw!!,
                keyAlias = envAlias!!,
                keyPassword = envKeyPw!!,
            )
        }
        else -> {
            val missing = pairs.filter { it.second == null }.joinToString(", ") { it.first }
            throw GradleException(
                "Partial RELEASE_KEYSTORE_* env vars detected: $missing not set. " +
                    "Either set all 4 (signed release build) OR none of them " +
                    "(unsigned-fallback build). See RELEASING.md → GitHub " +
                    "Secrets for the canonical 4-secret list."
            )
        }
    }
}

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

    // ─── Release signing (D2.10, D5.4, Story 7.3) ───────────────────────
    // The signingConfig is wired into buildTypes.release ONLY when all
    // four RELEASE_KEYSTORE_* env vars resolve at configuration time
    // (resolvedReleaseKeystore != null). Otherwise the block is skipped
    // entirely + assembleRelease produces app-release-unsigned.apk.
    signingConfigs {
        resolvedReleaseKeystore?.let { keystore ->
            create("release") {
                storeFile = keystore.storeFile
                storePassword = keystore.storePassword
                keyAlias = keystore.keyAlias
                keyPassword = keystore.keyPassword
                // v1+v2+v3 schemes all enabled (AGP defaults; restated for
                // documentation — Story 7.3 e2e smoke asserts all three).
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
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

        // Epic 9 Story 9.1 — SerpAPI key (optional, empty = SerpAPI disabled).
        // SerpApiClient reads this from BuildConfig.SERP_API_KEY.
        buildConfigField("String", "SERP_API_KEY", "\"$serpApiKeyForBuildConfig\"")

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
            // Story 7.3 — apply the release signingConfig when present.
            // resolvedReleaseKeystore == null → no signingConfig assigned →
            // assembleRelease produces app-release-unsigned.apk (graceful
            // fallback for local-dev workflows without a keystore + the
            // pre-keystore-provisioned CI path).
            resolvedReleaseKeystore?.let {
                signingConfig = signingConfigs.getByName("release")
            }

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
    // Epic 8 Story 8.1 — Downloadable Fonts for Figtree + EB Garamond
    // (VSTypography). GMS provider, cached after first download.
    implementation(libs.androidx.compose.ui.text.google.fonts)

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
