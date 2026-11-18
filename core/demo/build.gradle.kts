plugins {
    id("ecu.jvm.library")
}

dependencies {
    api(project(":core:obd"))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
}
