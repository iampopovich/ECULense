package io.github.iampopovich.eculense.core.ailocal

import ai.koog.prompt.executor.clients.litert.LiteRTLLModels
import ai.koog.prompt.llm.LLModel
import io.github.iampopovich.eculense.core.datapacks.PackFile
import io.github.iampopovich.eculense.core.datapacks.PackGroup
import io.github.iampopovich.eculense.core.datapacks.PackManifest
import androidx.annotation.StringRes
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest

@Serializable
enum class ModelOrigin(@param:StringRes val badgeRes: Int) {
    KOOG(R.string.ailocal_origin_koog),
    GALLERY(R.string.ailocal_origin_gallery),
    HF(R.string.ailocal_origin_hf),
    CUSTOM_LINK(R.string.ailocal_origin_custom_link),
    CUSTOM_FILE(R.string.ailocal_origin_custom_file),
}

/**
 * A model for the «На устройстве» screen. [fileName] = the model id in Koog (`LiteRTLLModels`): the Koog client opens
 * `modelsPath/<id>`. [packId] — data pack id (download/storage via :core:datapacks).
 * [repo] — HF repository (dedup key together with [fileName]); [koog] — the model is also in the Koog catalog;
 * [minRamGb]/[accelerators] — from the Google AI Edge Gallery allowlist; [actionsOnly] — a function-calling model, not for chat.
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

    /**
     * Text for the screen. Gallery descriptions come from Google's catalog and stay in its language; our own
     * descriptions are resources, so models stored by older versions also show in the app language.
     */
    val descriptionText: UiText
        get() = when (origin) {
            ModelOrigin.GALLERY -> UiText.Raw(description)
            ModelOrigin.HF -> UiText.Res(R.string.ailocal_desc_hf, listOf(repo ?: sourceUrl.removePrefix(HF_URL)))
            ModelOrigin.CUSTOM_LINK -> UiText.Res(R.string.ailocal_desc_custom_link, listOf(sourceUrl.removePrefix(HF_URL)))
            ModelOrigin.CUSTOM_FILE -> UiText.Res(R.string.ailocal_desc_custom_file)
            ModelOrigin.KOOG -> LocalModelCatalog.descriptionRes(fileName)?.let { UiText.Res(it) }
                ?: if (url == null) UiText.Res(R.string.ailocal_desc_koog_no_link) else UiText.Raw(description)
        }
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
 * Model catalog: first the Koog client's models (`LiteRTLLModels.models` — their ids are the file names), plus where
 * to download them on Hugging Face (litert-community; revision, size and LFS sha256 pinned 2026-11-18). Models that
 * Koog knows about but we don't yet are shown without a link — they can be added as «Свою модель».
 */
object LocalModelCatalog {
    private data class HfSource(
        val repo: String,
        val revision: String,
        val title: String,
        val description: String,
        @param:StringRes val descriptionRes: Int,
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
            description = "Tiny Google model for function calling (trained on phone actions). Needs a Hugging Face token and the accepted Gemma license.",
            descriptionRes = R.string.ailocal_desc_functiongemma,
            sizeBytes = 288_964_608L,
            sha256 = null, // the file is gated without a token — hash not published; size is checked
            gated = true,
            license = "Gemma Terms of Use",
        ),
        LiteRTLLModels.Gemma4E2B.id to HfSource(
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            revision = "2d74ac41a33d7da82f39f7d5720a04c68514a103",
            title = "Gemma 4 E2B",
            description = "Google Gemma 4, ≈2B effective parameters, understands tools. Open, no token needed.",
            descriptionRes = R.string.ailocal_desc_gemma4_e2b,
            sizeBytes = 2_588_147_712L,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            gated = false,
            license = "Apache-2.0",
        ),
        LiteRTLLModels.Gemma4E4B.id to HfSource(
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            revision = "f254b5ba6e36f7d89cc7892e268e9877bc20f0a0",
            title = "Gemma 4 E4B",
            description = "The larger Gemma 4 (≈4B effective parameters): more accurate than E2B, but slower and heavier. Open, no token needed.",
            descriptionRes = R.string.ailocal_desc_gemma4_e4b,
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
            description = s?.description ?: "A model from the Koog catalog; download link unknown — add it as a custom model.",
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

    /** Our own description of a Koog catalog model, by its file name. */
    @StringRes
    fun descriptionRes(fileName: String): Int? = sources[fileName]?.descriptionRes

    /** Pack id of a catalog model: derived from the file name (same as the Koog entries had — started downloads are not lost). */
    fun catalogId(fileName: String): String = "model-" + slug(fileName.removeSuffix(".litertlm"))

    /**
     * Unified list: Koog + Gallery + custom/from HF search. Duplicates by (repository, file) are merged: Gallery
     * metadata (name, description, RAM, accelerators), but Koog's revision/sha256/pack id — they are verified with the
     * Koog client runtime, and a started download continues. Custom models matching the catalog are not duplicated.
     * Sorted by size, smallest to largest; unknown size goes last.
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

    /** Pack id of a custom model: stable for the same link, safe for paths. */
    fun customId(repo: String, fileName: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest("$repo/$fileName".toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        return "model-custom-" + slug(fileName.removeSuffix(".litertlm")).take(40).trim('-') + "-" + hash
    }

    internal fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

internal const val HF_URL = "https://huggingface.co/"

/** Link to a `.litertlm` file on Hugging Face (resolve/blob, with revision). */
data class HfLink(val repo: String, val revision: String, val path: String) {
    val fileName: String get() = path.substringAfterLast('/')
    val resolveUrl: String get() = "https://huggingface.co/$repo/resolve/$revision/$path"
    val repoUrl: String get() = "https://huggingface.co/$repo"

    enum class Problem(@param:StringRes val messageRes: Int) {
        NOT_HF(R.string.ailocal_link_not_hf),
        NOT_LITERTLM(R.string.ailocal_link_not_litertlm),
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
