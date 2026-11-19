package io.github.iampopovich.eculense.core.datapacks

/** Pack file. sha256/size are known only for the pinned version; for an update they are null (response length is checked). */
data class PackFile(
    val name: String,
    val url: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

/** Where to find the latest pack version for the weekly update check. */
sealed interface UpdateSource {
    /** No check: the version changes only with an app release. */
    data object None : UpdateSource

    /**
     * GitHub releases: tag from the `https://github.com/<repo>/releases/latest` redirect (no REST API, it is rate-limited per IP).
     * files: file name → URL template with `{tag}` (release asset, or raw file at the tag if there are no assets).
     */
    data class GithubRelease(val repo: String, val files: Map<String, String>) : UpdateSource {
        fun fileUrl(tag: String, name: String) = files.getValue(name).replace("{tag}", tag)
    }

    /** Version is a JSON string field (OBDex: `meta.json` → `generated_at`); file URLs don't change. */
    data class JsonField(val url: String, val field: String) : UpdateSource
}

/**
 * Data pack description: what, from where, which version, under which license. Versions are pinned in code ([PackCatalog]);
 * update = new tag/source version, the file is taken unchanged (for ODbL, no derived database).
 */
data class PackManifest(
    val id: String,
    val title: String,
    val description: String,
    val version: String,
    val files: List<PackFile>,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String,
    /** Attribution line for «About» (English, as the sources publish it). */
    val attribution: String,
    val update: UpdateSource = UpdateSource.None,
    /** Reference packs are shown on the «Data and references» screen; AI models have their own screen (:core:ailocal). */
    val group: PackGroup = PackGroup.REFERENCE,
    /**
     * Localized title/description/license of the built-in reference packs (0 — none: user/AI model packs show [title],
     * [description] and [license] as is). The plain fields stay English for logs and tests.
     */
    val titleRes: Int = 0,
    val descriptionRes: Int = 0,
    val licenseRes: Int = 0,
    /**
     * Multi-gigabyte files: `.part` survives drops/stops and resumes where it left off (HTTP Range); the worker runs
     * as a foreground task and retries. For reference packs it's false: on error everything starts over.
     */
    val resumable: Boolean = false,
) {
    val sizeBytes: Long? get() = files.map { it.sizeBytes ?: return null }.sum()

    /** Files of a different version: for GitHub releases the URL depends on the tag, the new version's hash is unknown. */
    fun filesFor(version: String): List<PackFile> = when {
        version == this.version -> files
        update is UpdateSource.GithubRelease -> update.files.keys.map { PackFile(it, update.fileUrl(version, it)) }
        else -> files.map { it.copy(sizeBytes = null, sha256 = null) }
    }
}

enum class PackGroup { REFERENCE, MODEL }

/**
 * Authorization headers for a URL (Hugging Face token for gated models). Sent only to the request's original host:
 * after a redirect to a CDN the token is not sent. The token is not stored in the manifest and never reaches WorkManager.
 */
fun interface PackAuth {
    fun headersFor(url: String): Map<String, String>
}

/** Pack catalog that changes at runtime (user models added by link). */
fun interface PackSource {
    fun manifests(): List<PackManifest>
}

/** Version directory name: tags and ISO dates contain «:», which is better kept out of paths. */
internal fun versionDir(version: String): String = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
