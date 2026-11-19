plugins {
    id("ecu.android.compose")
}

// Localized text for typed OBD results (connection errors, PID names, built-in endpoints). Shared by the features
// that show them; :core:obd stays pure JVM and never sees resources.
dependencies {
    api(project(":core:obd"))
    api(project(":core:ui"))
}
