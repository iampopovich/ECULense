plugins {
    id("ecu.android.feature")
}

dependencies {
    // AppCompatDelegate.setApplicationLocales: in-app language picker (works on API 26+).
    implementation(libs.androidx.appcompat)
}
