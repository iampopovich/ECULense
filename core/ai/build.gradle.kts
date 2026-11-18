plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.koog.agents)
    // Клиенты провайдеров из koog-spring-boot-starter: OpenRouter, Google, DeepSeek, Mistral, Ollama
    // (OpenAI и Anthropic приходят с koog-agents).
    implementation(libs.koog.openrouter)
    implementation(libs.koog.google)
    implementation(libs.koog.deepseek)
    implementation(libs.koog.mistral)
    implementation(libs.koog.ollama)
    // Разбор ошибок клиентов Koog (SerializationException = сервер ответил не в формате API).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
