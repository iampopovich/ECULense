package io.github.iampopovich.eculense.core.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val aiModule = module {
    single<SecretCipher> { KeystoreCipher() }
    single { LlmSettingsStore(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("llm_settings") }, get()) }
    single { Room.databaseBuilder(androidContext(), AiChatDatabase::class.java, "ai_chat.db").build() }
    single<ChatHistoryStore> { RoomChatHistory(get<AiChatDatabase>().messages()) }
    single<LlmGateway> { KoogLlmGateway(get(), toolbox = getOrNull(), historyProvider = get()) }
    single<ConnectionChecker> { KoogConnectionChecker() }
    single<ChatPrefs> { DataStoreChatPrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("ai_chat") }) }
}
