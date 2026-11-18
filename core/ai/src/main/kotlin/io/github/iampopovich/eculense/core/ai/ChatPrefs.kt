package io.github.iampopovich.eculense.core.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Настройки чата: согласие на отправку данных машины (по провайдеру — сменил провайдера, спросим снова),
 * переключатель «данные моей машины» и текущий диалог.
 */
interface ChatPrefs {
    suspend fun hasConsent(providerKey: String): Boolean
    suspend fun grantConsent(providerKey: String)
    suspend fun revokeConsents()
    /** null — пользователь ещё не выбирал (по умолчанию включено). */
    suspend fun useCarData(): Boolean?
    suspend fun setUseCarData(enabled: Boolean)
    suspend fun conversationId(): String?
    suspend fun setConversationId(id: String)
}

internal class DataStoreChatPrefs(private val store: DataStore<Preferences>) : ChatPrefs {
    private val data get() = store.data

    override suspend fun hasConsent(providerKey: String) = data.map { it[CONSENT].orEmpty() }.first().contains(providerKey)

    override suspend fun grantConsent(providerKey: String) {
        store.edit { it[CONSENT] = it[CONSENT].orEmpty() + providerKey }
    }

    override suspend fun revokeConsents() {
        store.edit { it.remove(CONSENT) }
    }

    override suspend fun useCarData(): Boolean? = data.first()[USE_CAR_DATA]

    override suspend fun setUseCarData(enabled: Boolean) {
        store.edit { it[USE_CAR_DATA] = enabled }
    }

    override suspend fun conversationId(): String? = data.first()[CONVERSATION]

    override suspend fun setConversationId(id: String) {
        store.edit { it[CONVERSATION] = id }
    }

    private companion object {
        val CONSENT = stringSetPreferencesKey("car_data_consent")
        val USE_CAR_DATA = booleanPreferencesKey("use_car_data")
        val CONVERSATION = stringPreferencesKey("conversation_id")
    }
}
