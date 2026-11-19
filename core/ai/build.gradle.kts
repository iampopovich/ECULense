plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
    // AI chat history has its own database ai_chat.db (removed together with the module).
    id("ecu.android.room")
}

dependencies {
    implementation(libs.koog.agents)
    // Provider clients from koog-spring-boot-starter: OpenRouter, Google, DeepSeek, Mistral, Ollama
    // (OpenAI and Anthropic come with koog-agents).
    implementation(libs.koog.openrouter)
    implementation(libs.koog.google)
    implementation(libs.koog.deepseek)
    implementation(libs.koog.mistral)
    implementation(libs.koog.ollama)
    // Parsing Koog client errors (SerializationException = server replied in a non-API format).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    androidTestImplementation(libs.androidx.room.testing)
}
