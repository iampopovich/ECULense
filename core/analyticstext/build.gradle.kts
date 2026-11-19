plugins {
    id("ecu.android.library")
}

// Localized text for typed analytics results (findings, maintenance works, severities). Shared by the features and
// :core:background that show them; :core:analytics stays pure JVM and never sees resources.
dependencies {
    api(project(":core:analytics"))
}
