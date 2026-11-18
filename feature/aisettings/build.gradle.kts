plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:ai"))
    testImplementation(libs.androidx.datastore.preferences)
}
