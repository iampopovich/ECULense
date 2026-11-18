package io.github.iampopovich.eculense.core.ailocal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Запись allowlist Google AI Edge Gallery (`model_allowlists/<ver>.json`, Apache-2.0); лишние поля игнорируются. */
@Serializable
data class GalleryModel(
    val name: String,
    val modelId: String = "",
    val modelFile: String = "",
    val commitHash: String = "",
    val sizeInBytes: Long = 0,
    val minDeviceMemoryInGb: Int? = null,
    val description: String = "",
    val learnMoreUrl: String? = null,
    val llmSupportImage: Boolean = false,
    val taskTypes: List<String> = emptyList(),
    val defaultConfig: GalleryConfig = GalleryConfig(),
) {
    val accelerators: List<String> get() = defaultConfig.accelerators?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

@Serializable
data class GalleryConfig(val accelerators: String? = null)

@Serializable
private data class GalleryAllowlistFile(val models: List<GalleryModel> = emptyList())

/**
 * Основной каталог моделей — allowlist приложения Google AI Edge Gallery (github.com/google-ai-edge/gallery,
 * Apache-2.0): HF-репозиторий, файл, коммит, размер, минимум ОЗУ, ускорители. В приложении лежит копия
 * [BUNDLED_VERSION]; свежая подтягивается по запросу ([LocalModels.refreshCatalog]). Файл пишется вручную и
 * содержит висячие запятые — парсим терпимо.
 */
object GalleryAllowlist {
    const val BUNDLED_VERSION = "1_0_23"
    const val REPO_URL = "https://github.com/google-ai-edge/gallery"
    private const val RAW = "https://raw.githubusercontent.com/google-ai-edge/gallery/main/model_allowlists/"

    /** Задачи Gallery, для которых модель годится как чат. */
    private val CHAT_TASKS = setOf("llm_chat", "llm_agent_chat")

    /** Закрытые (gated) репозитории на 2026-11-19 — пока нет ответа API Hugging Face. */
    val KNOWN_GATED = setOf(
        "litert-community/Gemma3-1B-IT",
        "google/gemma-3n-E2B-it-litert-lm",
        "google/gemma-3n-E4B-it-litert-lm",
        "litert-community/functiongemma-270m-ft-mobile-actions",
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
        isLenient = true
        coerceInputValues = true
    }

    fun url(version: String): String = "$RAW$version.json"

    /** Следующая версия allowlist («1_0_23» → «1_0_24»): новые файлы получают больший номер. */
    fun next(version: String): String {
        val parts = version.split('_')
        val last = parts.last().toIntOrNull() ?: return version
        return (parts.dropLast(1) + (last + 1).toString()).joinToString("_")
    }

    fun parse(text: String): List<GalleryModel> = json.decodeFromString(GalleryAllowlistFile.serializer(), text).models

    fun bundledText(): String =
        requireNotNull(GalleryAllowlist::class.java.getResourceAsStream("/gallery/model_allowlist_$BUNDLED_VERSION.json")) { "bundled allowlist missing" }
            .bufferedReader().use { it.readText() }

    fun bundled(): List<GalleryModel> = parse(bundledText())

    /** Чат-модели `.litertlm` → записи каталога с закреплённой ссылкой `resolve/<commit>/<file>`. */
    fun toLocalModels(models: List<GalleryModel>, gated: Map<String, Boolean> = emptyMap()): List<LocalModel> =
        models.filter { m ->
            m.modelFile.endsWith(".litertlm") && m.modelId.contains('/') && m.commitHash.isNotBlank() && m.taskTypes.any { it in CHAT_TASKS }
        }.map { m ->
            LocalModel(
                packId = LocalModelCatalog.catalogId(m.modelFile),
                fileName = m.modelFile,
                title = m.name,
                description = plain(m.description),
                url = "https://huggingface.co/${m.modelId}/resolve/${m.commitHash}/${m.modelFile}",
                sizeBytes = m.sizeInBytes.takeIf { it > 0 },
                sha256 = null, // allowlist не даёт хэш; проверяется размер
                gated = gated[m.modelId] ?: (m.modelId in KNOWN_GATED),
                origin = ModelOrigin.GALLERY,
                license = "см. страницу модели",
                sourceUrl = m.learnMoreUrl ?: "https://huggingface.co/${m.modelId}",
                version = m.commitHash.take(12),
                repo = m.modelId,
                minRamGb = m.minDeviceMemoryInGb,
                accelerators = m.accelerators,
                supportsImage = m.llmSupportImage,
            )
        }

    /** Markdown-ссылки описаний «[текст](url)» → «текст». */
    fun plain(markdown: String): String = markdown.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1").trim()
}
