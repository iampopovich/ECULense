plugins {
    id("ecu.android.library")
}

dependencies {
    implementation(project(":core:obd"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
