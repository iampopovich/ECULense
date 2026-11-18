plugins {
    id("ecu.android.library")
}

dependencies {
    implementation(libs.koog.agents)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
