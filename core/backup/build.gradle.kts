plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// 1.27: background Car Scanner backup import (WorkManager, foreground notification with progress) and ECULense
// backup (.eculense: database, settings without keys, manifest). Backup logic has no android.*, tests on the JVM.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(libs.androidx.room.runtime) // Room connection for checkpoint + VACUUM INTO of our own database
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.work.testing)
}
