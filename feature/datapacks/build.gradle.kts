plugins {
    id("ecu.android.feature")
}

// «Настройки → Данные и справочники», «О приложении» (атрибуции), просмотр сигналсета OBDb активной машины.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:datapacks"))
    implementation(project(":core:obdb"))
}
