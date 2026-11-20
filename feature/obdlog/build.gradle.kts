plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:obd"))
    implementation(libs.androidx.core.ktx) // FileProvider for sharing the exported log
    implementation(libs.androidx.datastore.preferences)
}
