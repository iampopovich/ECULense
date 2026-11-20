plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.vico.compose.m3)
}
