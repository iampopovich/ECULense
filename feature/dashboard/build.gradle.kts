plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:obd"))
    implementation(project(":core:obdtext"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    testImplementation(testFixtures(project(":core:obd")))
}
