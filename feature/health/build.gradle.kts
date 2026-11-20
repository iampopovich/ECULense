plugins {
    id("ecu.android.feature")
}

// B6 quick health check on Pit-stop: one tap, traffic light per item, history, HTML report (share / print to PDF).
dependencies {
    implementation(project(":core:obd"))
    implementation(project(":core:obdtext"))
    implementation(project(":core:data"))
    implementation(project(":core:analyticstext"))
    implementation(libs.androidx.core.ktx) // FileProvider for the report
    testImplementation(testFixtures(project(":core:obd")))
}
