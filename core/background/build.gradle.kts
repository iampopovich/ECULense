plugins {
    id("ecu.android.library")
}

// Background analysis after a trip/import and a daily maintenance check (3.9 item 3): WorkManager + notifications.
// No network and no AI. The module is optional: without it the data layer recomputes findings itself.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:navigation"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    androidTestImplementation(libs.androidx.work.testing)
}
