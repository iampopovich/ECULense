plugins {
    id("ecu.android.feature")
}

// 3.10: модель на устройстве — провайдер «На устройстве» для LlmGateway (:core:ai), каталог Koog LiteRT, проверка
// телефона, скачивание через :core:datapacks, тест скорости в отдельном процессе :bench. Модуль удаляемый: убрать
// include + зависимость app + строку в AppModules — приложение и облачный ИИ работают без него.
dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:datapacks"))
    implementation(libs.koog.agents)
    implementation(libs.koog.litert)
    implementation(libs.litertlm.android)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    testImplementation(libs.androidx.datastore.preferences)
}
