package io.github.iampopovich.eculense.core.ailocal

import android.content.Context
import android.net.Uri
import io.github.iampopovich.eculense.core.datapacks.DataPacks
import io.github.iampopovich.eculense.core.datapacks.PackAuth
import io.github.iampopovich.eculense.core.datapacks.PackHttp
import io.github.iampopovich.eculense.core.datapacks.PackManifest
import io.github.iampopovich.eculense.core.datapacks.PackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Ссылка проверена: модель можно добавить; или понятная причина, почему нет. */
sealed interface LinkCheck {
    data class Ok(val model: LocalModel) : LinkCheck
    data class Failed(val message: String) : LinkCheck
}

/**
 * Модели «На устройстве»: каталог Koog + свои (по ссылке HF или из файла), их файлы — пакеты :core:datapacks
 * (группа MODEL, докачка). Сам является [PackSource] (каталог меняется на ходу) и [PackAuth] (токен HF — только для
 * huggingface.co, на CDN после редиректа не уходит).
 */
class LocalModels(
    private val context: Context,
    private val store: LocalModelStore,
    /** Провайдер, а не экземпляр: DataPacks сам собирается из PackSource/PackAuth (этот класс) — иначе цикл в Koin. */
    packsProvider: () -> DataPacks,
    private val http: PackHttp,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PackSource, PackAuth {
    private val packs: DataPacks by lazy(packsProvider)
    private val customState: StateFlow<List<LocalModel>> = store.custom.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val models: Flow<List<LocalModel>> = store.custom.map { LocalModelCatalog.koogEntries() + it }

    fun all(): List<LocalModel> = LocalModelCatalog.koogEntries() + customState.value

    fun find(packId: String): LocalModel? = all().firstOrNull { it.packId == packId }

    /** Файл скачанной/импортированной модели или null. */
    fun file(packId: String): File? = find(packId)?.let { packs.store.file(packId, it.fileName) }

    override fun manifests(): List<PackManifest> = all().mapNotNull { it.toPack() }

    override fun headersFor(url: String): Map<String, String> {
        val host = runCatching { URL(url).host }.getOrNull() ?: return emptyMap()
        if (host != "huggingface.co") return emptyMap()
        val token = runBlocking { store.token() } ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    /**
     * Своя модель по ссылке HF: HEAD без следования редиректу — размер и sha256 LFS-файла (`x-linked-size`,
     * `x-linked-etag`), ревизия (`x-repo-commit`, ссылка фиксируется на неё), 401/403 — закрытая модель.
     */
    suspend fun checkLink(raw: String): LinkCheck = withContext(Dispatchers.IO) {
        HfLink.check(raw)?.let { return@withContext LinkCheck.Failed(it.message) }
        val link = HfLink.parse(raw)!!
        val head = try {
            http.head(link.resolveUrl, headersFor(link.resolveUrl))
        } catch (e: IOException) {
            return@withContext LinkCheck.Failed("Нет связи с Hugging Face: ${e.message}")
        }
        when (head.code) {
            in 200..399 -> {
                val size = head.headers["x-linked-size"]?.toLongOrNull() ?: head.headers["content-length"]?.toLongOrNull()?.takeIf { head.code == 200 }
                val sha = head.headers["x-linked-etag"]?.trim('"')?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                val revision = head.headers["x-repo-commit"]?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: link.revision
                val pinned = link.copy(revision = revision)
                LinkCheck.Ok(
                    LocalModel(
                        packId = LocalModelCatalog.customId(link.repo, link.fileName),
                        fileName = link.fileName,
                        title = link.fileName.removeSuffix(".litertlm"),
                        description = "Своя модель: ${link.repo}",
                        url = pinned.resolveUrl,
                        sizeBytes = size,
                        sha256 = sha,
                        gated = false,
                        origin = ModelOrigin.CUSTOM_LINK,
                        license = "см. страницу модели",
                        sourceUrl = link.repoUrl,
                        version = revision.take(12),
                    ),
                )
            }
            401, 403 -> LinkCheck.Failed(
                if (store.token() == null) "Модель закрыта (gated): нужен токен Hugging Face и принятая лицензия на странице модели."
                else "Hugging Face отказал (${head.code}): примите лицензию модели на её странице или проверьте токен.",
            )
            404 -> LinkCheck.Failed("Файл не найден (404): проверьте ссылку.")
            else -> LinkCheck.Failed("Hugging Face ответил ${head.code}.")
        }
    }

    suspend fun add(model: LocalModel) {
        store.addCustom(model)
        packs.changed()
    }

    /** Удалить файл (и запись своей модели); тест и выбор этой модели забываются. */
    suspend fun delete(packId: String) {
        packs.delete(packId)
        store.forget(packId)
        if (find(packId)?.origin != ModelOrigin.KOOG) store.removeCustom(packId)
        packs.changed()
    }

    /** «Файл с телефона»: копия в хранилище моделей (атомарно, через staging пакета). */
    suspend fun importFile(uri: Uri, displayName: String, sizeBytes: Long?, progress: (Long) -> Unit): LocalModel = withContext(Dispatchers.IO) {
        require(displayName.endsWith(".litertlm")) { HfLink.Problem.NOT_LITERTLM.message }
        val model = LocalModel(
            packId = LocalModelCatalog.customId("file", displayName),
            fileName = displayName,
            title = displayName.removeSuffix(".litertlm"),
            description = "Своя модель из файла на телефоне",
            url = null,
            sizeBytes = sizeBytes,
            sha256 = null,
            gated = false,
            origin = ModelOrigin.CUSTOM_FILE,
            license = "",
            sourceUrl = "",
            version = "file",
        )
        val staging = packs.store.staging(model.packId, model.version)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("не удалось открыть файл")
            input.use { src ->
                File(staging, displayName).outputStream().buffered(1 shl 20).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done)
                    }
                }
            }
            packs.store.commit(model.packId, model.version, staging)
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        store.addCustom(model)
        packs.changed()
        model
    }
}
