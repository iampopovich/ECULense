plugins {
    id("ecu.android.compose")
}

// Test-only helper for C13 screenshot tests: device × font-scale matrix around Roborazzi + Robolectric.
// Only feature modules' testImplementation depends on it; never shipped in the app.
dependencies {
    api(project(":core:ui"))
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.robolectric)
    api(libs.junit)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.test.manifest)
}
