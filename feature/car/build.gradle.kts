plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:analytics"))
    implementation(project(":core:analyticstext"))
    implementation(project(":core:datapacks"))
    implementation(project(":core:vehicledb"))
}
