plugins {
    id("ecu.android.feature")
    id("ecu.android.screenshots")
}

dependencies {
    implementation(project(":core:obd"))
    implementation(project(":core:obdtext"))
    implementation(project(":core:data"))
    implementation(project(":core:dtcdb"))
    testImplementation(testFixtures(project(":core:obd")))
}
