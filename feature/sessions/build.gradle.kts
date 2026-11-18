plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:obd")) // имена PID для подписей
    implementation(libs.vico.compose.m3)
}
