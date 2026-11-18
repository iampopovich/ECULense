package io.github.iampopovich.eculense.core.ailocal

import ai.koog.prompt.executor.clients.litert.LiteRTLLModels
import ai.koog.prompt.llm.LLModel
import io.github.iampopovich.eculense.core.datapacks.PackFile
import io.github.iampopovich.eculense.core.datapacks.PackGroup
import io.github.iampopovich.eculense.core.datapacks.PackManifest
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest

@Serializable
enum class ModelOrigin(val badge: String) { KOOG("Koog"), GALLERY("Gallery"), HF("HF"), CUSTOM_LINK("своя"), CUSTOM_FILE("файл") }

/**
 * Модель для экрана «На устройстве». [fileName] = id модели в Koog (`LiteRTLLModels`): клиент Koog открывает
 * `modelsPath/<id>`. [packId] — id пакета данных (скачивание/хранение через :core:datapacks).
 * [repo] — репозиторий HF (ключ дедупликации вместе с [fileName]); [koog] — модель есть и в каталоге Koog;
 * [minRamGb]/[accelerators] — из allowlist Google AI Edge Gallery; [actionsOnly] — модель для вызова функций, не для чата.
 */
@Serializable
data class LocalModel(
    val packId: String,
    val fileName: String,
    val title: String,
    val description: String,
    val url: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val gated: Boolean,
    val origin: ModelOrigin,
    val license: String,
    val sourceUrl: String,
    val version: String,
    val repo: String? = null,
    val koog: Boolean = false,
    val minRamGb: Int? = null,
    val accelerators: List<String> = emptyList(),
    val actionsOnly: Boolean = false,
    val supportsImage: Boolean = false,
) {
    val key: Pair<String?, String> get() = repo to fileName
    fun toPack(): PackManifest? {
        val link = url ?: return null
        return PackManifest(
            id = packId,
            title = title,
            description = description,
            version = version,
            files = listOf(PackFile(fileName, link, sizeBytes, sha256)),
            license = license,
            licenseUrl = sourceUrl,
            sourceUrl = sourceUrl,
            attribution = "$title — $sourceUrl",
            group = PackGroup.MODEL,
            resumable = true,
        )
    }
}

/**
 * Каталог моделей: сначала модели клиента Koog (`LiteRTLLModels.models` — их id и есть имена файлов), к ним — откуда
 * скачать на Hugging Face (litert-community; ревизия, размер и sha256 LFS зафиксированы 2026-11-18). Модели, о которых
 * Koog знает, а мы ещё нет, видны без ссылки — их можно добавить как «Свою модель».
 */
object LocalModelCatalog {
    private data class HfSource(
        val repo: String,
        val revision: String,
        val title: String,
        val description: String,
        val sizeBytes: Long,
        val sha256: String?,
        val gated: Boolean,
        val license: String,
    )

    private val sources: Map<String, HfSource> = mapOf(
        LiteRTLLModels.FunctionGemma.id to HfSource(
            repo = "litert-community/functiongemma-270m-ft-mobile-actions",
            revision = "322205aa5b7b724bd3afa025a760a7577078ac6a",
            title = "FunctionGemma 270M",
            description = "Крошечная модель Google для вызова функций (обучена на действиях телефона). Нужен токен Hugging Face и принятая лицензия Gemma.",
            sizeBytes = 288_964_608L,
            sha256 = null, // файл закрыт без токена — хэш не опубликован; проверяется размер
            gated = true,
            license = "Gemma Terms of Use",
        ),
        LiteRTLLModels.Gemma4E2B.id to HfSource(
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            revision = "2d74ac41a33d7da82f39f7d5720a04c68514a103",
            title = "Gemma 4 E2B",
            description = "Google Gemma 4, ≈2 млрд эффективных параметров, понимает инструменты. Открыта без токена.",
            sizeBytes = 2_588_147_712L,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            gated = false,
            license = "Apache-2.0",
        ),
        LiteRTLLModels.Gemma4E4B.id to HfSource(
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            revision = "f254b5ba6e36f7d89cc7892e268e9877bc20f0a0",
            title = "Gemma 4 E4B",
            description = "Старшая Gemma 4 (≈4 млрд эффективных параметров): точнее E2B, но медленнее и тяжелее. Открыта без токена.",
            sizeBytes = 3_659_530_240L,
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            gated = false,
            license = "Apache-2.0",
        ),
    )

    fun koogEntries(models: List<LLModel> = LiteRTLLModels.models): List<LocalModel> = models.map { m ->
        val s = sources[m.id]
        LocalModel(
            packId = "model-" + slug(m.id.removeSuffix(".litertlm")),
            fileName = m.id,
            title = s?.title ?: m.id.removeSuffix(".litertlm"),
            description = s?.description ?: "Модель из каталога Koog; ссылка для скачивания неизвестна — добавьте её как свою модель.",
            url = s?.let { "https://huggingface.co/${it.repo}/resolve/${it.revision}/${m.id}" },
            sizeBytes = s?.sizeBytes,
            sha256 = s?.sha256,
            gated = s?.gated ?: false,
            origin = ModelOrigin.KOOG,
            license = s?.license.orEmpty(),
            sourceUrl = s?.let { "https://huggingface.co/${it.repo}" }.orEmpty(),
            version = s?.revision?.take(12) ?: "koog",
            repo = s?.repo,
            koog = true,
            actionsOnly = m.id == LiteRTLLModels.FunctionGemma.id,
        )
    }

    /** id пакета модели каталога: из имени файла (тот же, что был у записей Koog — начатые загрузки не теряются). */
    fun catalogId(fileName: String): String = "model-" + slug(fileName.removeSuffix(".litertlm"))

    /**
     * Единый список: Koog + Gallery + свои/из поиска HF. Дубликаты по (репозиторий, файл) склеиваются: метаданные
     * Gallery (название, описание, ОЗУ, ускорители), но ревизия/sha256/id пакета Koog — они проверены с рантаймом
     * клиента Koog, и начатая загрузка продолжается. Свои модели, совпадающие с каталогом, не дублируются.
     * Сортировка — по размеру от меньшей к большей, неизвестный размер — в конце.
     */
    fun merge(koog: List<LocalModel>, gallery: List<LocalModel>, custom: List<LocalModel>): List<LocalModel> {
        val byKey = LinkedHashMap<Pair<String?, String>, LocalModel>()
        gallery.forEach { byKey.putIfAbsent(it.key, it) }
        koog.forEach { k ->
            val g = byKey[k.key]
            byKey[k.key] = if (g == null || k.url == null) k else g.copy(
                packId = k.packId,
                url = k.url,
                sizeBytes = k.sizeBytes ?: g.sizeBytes,
                sha256 = k.sha256 ?: g.sha256,
                version = k.version,
                license = k.license.ifBlank { g.license },
                gated = g.gated || k.gated,
                koog = true,
            )
        }
        val catalogIds = byKey.values.map { it.packId }.toSet()
        custom.forEach { c -> if (c.packId !in catalogIds) byKey.putIfAbsent(c.key, c) }
        return byKey.values.sortedWith(compareBy<LocalModel> { it.sizeBytes ?: Long.MAX_VALUE }.thenBy { it.title.lowercase() })
    }

    /** id пакета своей модели: стабилен для одной ссылки, безопасен для путей. */
    fun customId(repo: String, fileName: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest("$repo/$fileName".toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        return "model-custom-" + slug(fileName.removeSuffix(".litertlm")).take(40).trim('-') + "-" + hash
    }

    internal fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

/** Ссылка на файл `.litertlm` на Hugging Face (resolve/blob, с ревизией). */
data class HfLink(val repo: String, val revision: String, val path: String) {
    val fileName: String get() = path.substringAfterLast('/')
    val resolveUrl: String get() = "https://huggingface.co/$repo/resolve/$revision/$path"
    val repoUrl: String get() = "https://huggingface.co/$repo"

    enum class Problem(val message: String) {
        NOT_HF("Нужна ссылка https://huggingface.co/…/resolve/… или …/blob/… на файл"),
        NOT_LITERTLM("Нужен файл .litertlm (формат LiteRT-LM); GGUF и другие форматы не подойдут"),
    }

    companion object {
        private val pattern = Regex("""^https://huggingface\.co/([^/]+/[^/]+)/(?:resolve|blob)/([^/]+)/(.+)$""")

        fun parse(raw: String): HfLink? {
            val trimmed = raw.trim().let { if (it.startsWith("huggingface.co/")) "https://$it" else it }
            val noQuery = trimmed.substringBefore('?').substringBefore('#')
            val m = pattern.find(noQuery) ?: return null
            val path = runCatching { URI("http://x/" + m.groupValues[3]).path.removePrefix("/") }.getOrNull() ?: return null
            if (!path.endsWith(".litertlm")) return null
            return HfLink(m.groupValues[1], m.groupValues[2], path)
        }

        fun check(raw: String): Problem? {
            val t = raw.trim()
            if (!(t.startsWith("https://huggingface.co/") || t.startsWith("huggingface.co/"))) return Problem.NOT_HF
            if (!t.substringBefore('?').endsWith(".litertlm")) return Problem.NOT_LITERTLM
            return if (parse(t) == null) Problem.NOT_HF else null
        }
    }
}
