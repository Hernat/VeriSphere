// Top-level build file. Plugins are declared here with `apply false`
// so the version-catalog references resolve once for the whole project;
// individual modules (currently only `:app`) apply them in their own
// build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
