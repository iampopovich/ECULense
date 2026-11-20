plugins {
    id("ecu.android.feature")
    id("ecu.android.screenshots")
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.vico.compose.m3)
}
