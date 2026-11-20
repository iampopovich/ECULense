plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:obd"))
    implementation(project(":core:obdtext"))
    implementation(project(":core:data"))
    testImplementation(testFixtures(project(":core:obd")))
}
