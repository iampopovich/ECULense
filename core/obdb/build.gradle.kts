plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// OBDb signalset v3 (CC BY-SA 4.0): download and parsing by make/model/year, the command planner (ELM steps) and the
// signal decoder for extended sensors (R-E). :core:obd stays free of the OBDb format.
dependencies {
    implementation(project(":core:datapacks"))
    api(project(":core:obd"))
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    // OBDb test cases (CC BY-SA 4.0, NOTICE in core/demo resources/obdb) as test vectors; :core:demo is debug-only.
    testImplementation(project(":core:demo"))
    testImplementation(testFixtures(project(":core:obd")))
}
