package io.github.iampopovich.eculense.core.ailocal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * Публичный API Hugging Face (без токена): статус gated репозитория и поиск «Другие модели» в litert-community —
 * `/api/models?author=…&search=…` (только id), затем `/api/models/<repo>?blobs=true` (файлы с размерами и sha256,
 * коммит `sha` — ссылка закрепляется на него).
 */
object HfApi {
    const val AUTHOR = "litert-community"
    private val json = Json { ignoreUnknownKeys = true }

    /** Файлы, собранные под конкретный NPU (MediaTek/Qualcomm/…): с GPU/CPU-бэкендом не запускаются. */
    private val NPU_FILE = Regex("""(?i)[._](mediatek|qualcomm|google_tensor|tensor|samsung|exynos|mt\d{4}|sm\d{4})[._]""")

    fun modelUrl(repo: String) = "https://huggingface.co/api/models/$repo"
    fun repoBlobsUrl(repo: String) = "https://huggingface.co/api/models/$repo?blobs=true"
    fun searchUrl(query: String, limit: Int = 20) =
        "https://huggingface.co/api/models?author=$AUTHOR&search=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=$limit"

    /** `gated`: false — открыт; "auto"/"manual" — нужен токен и принятая лицензия; null — поля нет. */
    fun gated(body: String): Boolean? {
        val g = json.parseToJsonElement(body).jsonObject["gated"] as? JsonPrimitive ?: return null
        return g.booleanOrNull ?: (g.contentOrNull?.isNotBlank() == true)
    }

    fun searchRepos(body: String): List<String> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }

    /** Файлы `.litertlm` репозитория (без NPU-сборок) → модели «из HF» с закреплённой ревизией, размером и sha256. */
    fun repoModels(body: String): List<LocalModel> {
        val o = json.parseToJsonElement(body).jsonObject
        val repo = o["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val sha = o["sha"]?.jsonPrimitive?.contentOrNull ?: "main"
        val gated = gated(body) == true
        val license = (o["cardData"] as? JsonObject)?.get("license")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "см. страницу модели"
        val siblings = o["siblings"] as? JsonArray ?: return emptyList()
        return siblings.mapNotNull { s ->
            val f = s as? JsonObject ?: return@mapNotNull null
            val name = f["rfilename"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!name.endsWith(".litertlm") || NPU_FILE.containsMatchIn(name)) return@mapNotNull null
            val lfs = f["lfs"] as? JsonObject
            val fileName = name.substringAfterLast('/')
            LocalModel(
                packId = LocalModelCatalog.customId(repo, fileName),
                fileName = fileName,
                title = fileName.removeSuffix(".litertlm"),
                description = "Модель из Hugging Face ($repo). Не проверена ни Google, ни нами — скачивание на ваш риск.",
                url = "https://huggingface.co/$repo/resolve/$sha/$name",
                sizeBytes = f["size"]?.jsonPrimitive?.longOrNull ?: lfs?.get("size")?.jsonPrimitive?.longOrNull,
                sha256 = lfs?.get("sha256")?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[0-9a-f]{64}")) },
                gated = gated,
                origin = ModelOrigin.HF,
                license = license,
                sourceUrl = "https://huggingface.co/$repo",
                version = sha.take(12),
                repo = repo,
            )
        }
    }
}
