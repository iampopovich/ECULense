plugins {
    id("ecu.android.library")
}

// E5-lite: phone accelerometer capture during recordings (Android SensorManager); math lives in :core:analytics.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:obd"))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
