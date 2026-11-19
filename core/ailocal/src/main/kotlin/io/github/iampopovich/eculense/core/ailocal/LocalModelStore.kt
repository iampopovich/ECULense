package io.github.iampopovich.eculense.core.ailocal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.iampopovich.eculense.core.ai.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Selected local model (package id) and the backend it runs on in chat. */
data class ActiveModel(val packId: String, val backend: BackendKind)

/**
 * «На устройстве» settings: selected model and backend, test results, custom models, Hugging Face token
 * (encrypted with an Android Keystore key, the same [SecretCipher] as API keys; never logged).
 */
class LocalModelStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val reportsSerializer = MapSerializer(String.serializer(), BenchReport.serializer())
    private val customSerializer = ListSerializer(LocalModel.serializer())

    val active: Flow<ActiveModel?> = dataStore.data.map { p ->
        val id = p[ACTIVE] ?: return@map null
        val backend = p[BACKEND]?.let { b -> BackendKind.entries.firstOrNull { it.name == b } } ?: BackendKind.CPU
        ActiveModel(id, backend)
    }

    val reports: Flow<Map<String, BenchReport>> = dataStore.data.map { p ->
        p[REPORTS]?.let { runCatching { json.decodeFromString(reportsSerializer, it) }.getOrNull() }.orEmpty()
    }

    val custom: Flow<List<LocalModel>> = dataStore.data.map { p ->
        p[CUSTOM]?.let { runCatching { json.decodeFromString(customSerializer, it) }.getOrNull() }.orEmpty()
    }

    val hasToken: Flow<Boolean> = dataStore.data.map { it[TOKEN] != null }

    suspend fun activate(packId: String, backend: BackendKind) {
        dataStore.edit {
            it[ACTIVE] = packId
            it[BACKEND] = backend.name
        }
    }

    suspend fun deactivate() {
        dataStore.edit {
            it.remove(ACTIVE)
            it.remove(BACKEND)
        }
    }

    suspend fun saveReport(report: BenchReport) {
        dataStore.edit { p ->
            val all = p[REPORTS]?.let { runCatching { json.decodeFromString(reportsSerializer, it) }.getOrNull() }.orEmpty()
            p[REPORTS] = json.encodeToString(reportsSerializer, all + (report.modelId to report))
        }
    }

    /** Model deleted: its test and selection are no longer valid. */
    suspend fun forget(packId: String) {
        dataStore.edit { p ->
            val all = p[REPORTS]?.let { runCatching { json.decodeFromString(reportsSerializer, it) }.getOrNull() }.orEmpty()
            p[REPORTS] = json.encodeToString(reportsSerializer, all - packId)
            if (p[ACTIVE] == packId) {
                p.remove(ACTIVE)
                p.remove(BACKEND)
            }
        }
    }

    suspend fun addCustom(model: LocalModel) {
        dataStore.edit { p ->
            val list = p[CUSTOM]?.let { runCatching { json.decodeFromString(customSerializer, it) }.getOrNull() }.orEmpty()
            p[CUSTOM] = json.encodeToString(customSerializer, list.filterNot { it.packId == model.packId } + model)
        }
    }

    suspend fun removeCustom(packId: String) {
        dataStore.edit { p ->
            val list = p[CUSTOM]?.let { runCatching { json.decodeFromString(customSerializer, it) }.getOrNull() }.orEmpty()
            p[CUSTOM] = json.encodeToString(customSerializer, list.filterNot { it.packId == packId })
        }
    }

    /** null or empty string deletes the token. */
    suspend fun setToken(token: String?) {
        dataStore.edit {
            val t = token?.trim()
            if (t.isNullOrEmpty()) it.remove(TOKEN) else it[TOKEN] = cipher.encrypt(t)
        }
    }

    suspend fun token(): String? = dataStore.data.first()[TOKEN]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    private companion object {
        val ACTIVE = stringPreferencesKey("active_model")
        val BACKEND = stringPreferencesKey("active_backend")
        val REPORTS = stringPreferencesKey("bench_reports")
        val CUSTOM = stringPreferencesKey("custom_models")
        val TOKEN = stringPreferencesKey("hf_token")
    }
}
