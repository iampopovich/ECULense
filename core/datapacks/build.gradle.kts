plugins {
    id("ecu.android.library")
}

// On-demand data packs (1.25b/1.26): reference databases are downloaded after install, they are not in the APK.
dependencies {
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(libs.sqlite.jdbc)
}
