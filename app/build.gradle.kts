import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ─── version.properties (single source of truth, D5.11) ──────────────────
val versionProps = Properties().apply {
    load(FileInputStream(rootProject.file("version.properties")))
}
val versionMajor: Int = versionProps.getProperty("MAJOR").toInt()
val versionMinor: Int = versionProps.getProperty("MINOR").toInt()
val versionPatch: Int = versionProps.getProperty("PATCH").toInt()
val resolvedVersionName: String = "$versionMajor.$versionMinor.$versionPatch"
val resolvedVersionCode: Int = versionMajor * 10_000 + versionMinor * 100 + versionPatch

// ─── GEMINI_API_KEY resolution (D2.2 / D2.9) ─────────────────────────────
// Order: local.properties (dev) → GEMINI_API_KEY env var (CI). Fail fast
// if neither source provides a value — no obfuscation, no silent default.
val resolvedGeminiApiKey: String = run {
    val localPropsFile = rootProject.file("local.properties")
    val fromLocalProps: String? = if (localPropsFile.exists()) {
        Properties().apply { load(FileInputStream(localPropsFile)) }
            .getProperty("GEMINI_API_KEY")
            ?.takeIf { it.isNotBlank() }
    } else null

    fromLocalProps
        ?: System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
        ?: throw GradleException(
            "GEMINI_API_KEY missing. Set it in local.properties (e.g. " +
                "GEMINI_API_KEY=AIza...) or export it as an environment " +
                "variable before building. See CONTRIBUTING.md."
        )
}

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
        buildConfigField("String", "GEMINI_API_KEY", "\"$resolvedGeminiApiKey\"")
    }

    buildTypes {
        debug {
            // Story 1.5 honours this flag in RateLimitRepositoryImpl.
            // Flip to "true" locally for testing without committing.
            buildConfigField("boolean", "SKIP_RATE_LIMIT", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
