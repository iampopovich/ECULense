plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:obd")) // PID names for labels
    implementation(libs.vico.compose.m3)
}
