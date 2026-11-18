plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// 1.27: фоновый импорт бэкапа Car Scanner (WorkManager, foreground-уведомление с прогрессом) и резервная копия
// ECULense (.eculense: база, настройки без ключей, манифест). Логика копии — без android.*, тесты на JVM.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(libs.androidx.room.runtime) // соединение Room для checkpoint + VACUUM INTO своей базы
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.work.testing)
}
