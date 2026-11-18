plugins {
    id("ecu.android.library")
    id("ecu.android.room")
}

dependencies {
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    androidTestImplementation(libs.androidx.room.testing)
}
