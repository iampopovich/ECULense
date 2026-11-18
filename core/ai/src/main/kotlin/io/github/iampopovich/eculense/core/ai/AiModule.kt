package io.github.iampopovich.eculense.core.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val aiModule = module {
    single<SecretCipher> { KeystoreCipher() }
    single { LlmSettingsStore(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("llm_settings") }, get()) }
    single<LlmGateway> { KoogLlmGateway(get()) }
    single<ConnectionChecker> { KoogConnectionChecker() }
}
