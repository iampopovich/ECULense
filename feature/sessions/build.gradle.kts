plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:obdtext")) // PID names for labels
    implementation(libs.vico.compose.m3)
}
