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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Link checked: the model can be added; or a clear reason why not. */
sealed interface LinkCheck {
    data class Ok(val model: LocalModel) : LinkCheck
    data class Failed(val message: String) : LinkCheck
}

/**
 * «На устройстве» models: Koog catalog + custom ones (via HF link or from a file); their files are :core:datapacks packs
 * (MODEL group, resumable). It is itself a [PackSource] (the catalog changes on the fly) and a [PackAuth] (the HF token is
 * only for huggingface.co, it is not sent to the CDN after a redirect).
 */
class LocalModels(
    private val context: Context,
    private val store: LocalModelStore,
    /** A provider, not an instance: DataPacks is itself built from PackSource/PackAuth (this class) — otherwise a Koin cycle. */
    packsProvider: () -> DataPacks,
    private val http: PackHttp,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PackSource, PackAuth {
    private val packs: DataPacks by lazy(packsProvider)
    private val customState: StateFlow<List<LocalModel>> = store.custom.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val cacheDir = File(context.filesDir, "ailocal")
    private val galleryState = MutableStateFlow(loadGallery())

    val models: Flow<List<LocalModel>> = combine(store.custom, galleryState) { custom, gallery -> LocalModelCatalog.merge(LocalModelCatalog.koogEntries(), gallery, custom) }

    fun all(): List<LocalModel> = LocalModelCatalog.merge(LocalModelCatalog.koogEntries(), galleryState.value, customState.value)

    /** Gallery allowlist version the catalog was built from («1_0_23»). */
    val catalogVersion: String get() = cachedVersion() ?: GalleryAllowlist.BUNDLED_VERSION

    private fun cachedVersion(): String? = File(cacheDir, "allowlist.version").takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }

    /** Downloaded allowlist copy (if present and readable), otherwise the bundled one; gated — from the HF API response or a known list. */
    private fun loadGallery(): List<LocalModel> {
        val text = File(cacheDir, "allowlist.json").takeIf { it.exists() }?.let { f -> runCatching { f.readText().also { GalleryAllowlist.parse(it) } }.getOrNull() }
        val models = runCatching { GalleryAllowlist.parse(text ?: GalleryAllowlist.bundledText()) }.getOrElse { GalleryAllowlist.bundled() }
        return GalleryAllowlist.toLocalModels(models, loadGated())
    }

    private fun loadGated(): Map<String, Boolean> = File(cacheDir, "gated.txt").takeIf { it.exists() }?.readLines()
        ?.mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to (it[1] == "1") } }?.toMap().orEmpty()

    /**
     * Refresh the catalog on request: Gallery allowlist — upward from the known version while the next number exists
     * (new files get a higher number), then the gated status of each repository from the Hugging Face API. Offline —
     * the previous (bundled) catalog stays. Returns the version or null if the refresh failed.
     */
    suspend fun refreshCatalog(): String? = withContext(Dispatchers.IO) {
        var version = catalogVersion
        var text: String? = null
        runCatching { text = getText(GalleryAllowlist.url(version)) }
        repeat(MAX_VERSION_PROBES) {
            val next = GalleryAllowlist.next(version)
            val t = runCatching { getText(GalleryAllowlist.url(next)) }.getOrNull() ?: return@repeat
            version = next
            text = t
        }
        val body = text ?: return@withContext null
        val parsed = runCatching { GalleryAllowlist.parse(body) }.getOrNull() ?: return@withContext null
        val repos = GalleryAllowlist.toLocalModels(parsed).mapNotNull { it.repo }.distinct()
        val gated = repos.mapNotNull { repo -> runCatching { HfApi.gated(getText(HfApi.modelUrl(repo))) }.getOrNull()?.let { repo to it } }.toMap()
        cacheDir.mkdirs()
        File(cacheDir, "allowlist.json").writeText(body)
        File(cacheDir, "allowlist.version").writeText(version)
        File(cacheDir, "gated.txt").writeText(gated.entries.joinToString("\n") { "${it.key}\t${if (it.value) 1 else 0}" })
        galleryState.value = GalleryAllowlist.toLocalModels(parsed, gated)
        packs.changed()
        version
    }

    /**
     * «Другие модели»: search of litert-community on Hugging Face. Files come with size and sha256, the link is pinned to
     * a commit. Anything already in the catalog is not repeated. The models are unvetted — at the user's own risk.
     */
    suspend fun search(query: String): List<LocalModel> = withContext(Dispatchers.IO) {
        val known = all().map { it.key }.toSet()
        val repos = HfApi.searchRepos(getText(HfApi.searchUrl(query))).take(MAX_SEARCH_REPOS)
        coroutineScope {
            repos.map { repo -> async { runCatching { HfApi.repoModels(getText(HfApi.repoBlobsUrl(repo))) }.getOrDefault(emptyList()) } }.awaitAll()
        }.flatten().filter { it.key !in known }.sortedBy { it.sizeBytes ?: Long.MAX_VALUE }
    }

    private fun getText(url: String): String = http.get(url).use { it.body.readBytes().decodeToString() }

    fun find(packId: String): LocalModel? = all().firstOrNull { it.packId == packId }

    /** File of the downloaded/imported model, or null. */
    fun file(packId: String): File? = find(packId)?.let { packs.store.file(packId, it.fileName) }

    override fun manifests(): List<PackManifest> = all().mapNotNull { it.toPack() }

    override fun headersFor(url: String): Map<String, String> {
        val host = runCatching { URL(url).host }.getOrNull() ?: return emptyMap()
        if (host != "huggingface.co") return emptyMap()
        val token = runBlocking { store.token() } ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $token")
    }

    /**
     * Custom model via HF link: HEAD without following the redirect — size and sha256 of the LFS file (`x-linked-size`,
     * `x-linked-etag`), revision (`x-repo-commit`, the link is pinned to it), 401/403 — gated model.
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

    /** Delete the file (and the custom model entry); the test and selection of this model are forgotten. */
    suspend fun delete(packId: String) {
        packs.delete(packId)
        store.forget(packId)
        if (find(packId)?.origin in CUSTOM_ORIGINS) store.removeCustom(packId)
        packs.changed()
    }

    private companion object {
        val CUSTOM_ORIGINS = setOf(ModelOrigin.CUSTOM_LINK, ModelOrigin.CUSTOM_FILE, ModelOrigin.HF)
        const val MAX_VERSION_PROBES = 5
        const val MAX_SEARCH_REPOS = 12
    }

    /** «Файл с телефона»: copy into model storage (atomically, via the pack staging). */
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
