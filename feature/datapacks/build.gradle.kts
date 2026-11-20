plugins {
    id("ecu.android.feature")
}

// «Настройки → Данные и справочники», «О приложении» (attributions), viewing the active car's OBDb signalset.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:datapacks"))
    implementation(project(":core:obdb"))
    implementation(project(":core:obd")) // C11: adapter version and protocol for the problem report
}
