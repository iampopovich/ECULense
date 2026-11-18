plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:datapacks"))
    implementation(project(":core:vehicledb"))
}
