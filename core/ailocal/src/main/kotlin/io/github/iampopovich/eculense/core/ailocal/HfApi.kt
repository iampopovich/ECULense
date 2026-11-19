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
 * Public Hugging Face API (no token): gated status of a repository and the «Другие модели» search in litert-community:
 * `/api/models?author=…&search=…` (ids only), then `/api/models/<repo>?blobs=true` (files with sizes and sha256,
 * commit `sha`, which the link is pinned to).
 */
object HfApi {
    const val AUTHOR = "litert-community"
    private val json = Json { ignoreUnknownKeys = true }

    /** Files built for a specific NPU (MediaTek/Qualcomm/…): they do not run on the GPU/CPU backend. */
    private val NPU_FILE = Regex("""(?i)[._](mediatek|qualcomm|google_tensor|tensor|samsung|exynos|mt\d{4}|sm\d{4})[._]""")

    fun modelUrl(repo: String) = "https://huggingface.co/api/models/$repo"
    fun repoBlobsUrl(repo: String) = "https://huggingface.co/api/models/$repo?blobs=true"
    fun searchUrl(query: String, limit: Int = 20) =
        "https://huggingface.co/api/models?author=$AUTHOR&search=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=$limit"

    /** `gated`: false = open; "auto"/"manual" = token and accepted license required; null = field absent. */
    fun gated(body: String): Boolean? {
        val g = json.parseToJsonElement(body).jsonObject["gated"] as? JsonPrimitive ?: return null
        return g.booleanOrNull ?: (g.contentOrNull?.isNotBlank() == true)
    }

    fun searchRepos(body: String): List<String> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }

    /** Repository `.litertlm` files (excluding NPU builds) → "from HF" models with pinned revision, size and sha256. */
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
