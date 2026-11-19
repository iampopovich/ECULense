package io.github.iampopovich.eculense.core.obdb

import io.github.iampopovich.eculense.core.datapacks.PackHttp
import io.github.iampopovich.eculense.core.datapacks.PackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Car profile fields the OBDb lookup needs. */
enum class ProfileField { MAKE, MODEL, YEAR }

/** Result of the OBDb check for the user's car. */
sealed interface SignalsetResult {
    /** The profile has no make/model/year: nothing to look up. */
    data class NeedsProfile(val missing: List<ProfileField>) : SignalsetResult

    /** No `OBDb/<Make>-<Model>` repository. */
    data class NoRepository(val repo: String) : SignalsetResult

    /** The repository exists, but there is no real-car data for this year's generation (Kia Sorento BL 2002–2009). */
    data class NotCovered(val repo: String, val year: Int, val generation: Generation?, val coveredYears: List<Int>) : SignalsetResult

    data class Covered(
        val repo: String,
        val year: Int,
        val generation: Generation?,
        val version: String,
        val ecus: List<Ecu>,
        val commands: List<SignalCommand>,
    ) : SignalsetResult {
        val signalCount: Int get() = commands.sumOf { it.signals.size }
    }
}

/**
 * OBDb (github.com/OBDb, CC BY-SA 4.0): one repository per model, `signalsets/v3/default.json` + `generations.yaml`.
 * A year is covered if the repository has test data (`tests/test_cases/<year>/command_support.yaml`) for at least one
 * year of the same generation; the commands themselves are filtered by `filter` for the car's year.
 * Download and viewing only: polling extended PIDs requires ATH1/ATSH and services 21/22 (backlog 1.24 items 6–7).
 */
class ObdbSignalsets(
    private val http: PackHttp,
    private val store: PackStore,
    private val rawBase: String = "https://raw.githubusercontent.com",
    private val currentYear: () -> Int = { java.time.Year.now().value },
) {
    fun repoName(make: String, model: String): String =
        "OBDb/" + listOf(make, model).joinToString("-") { part -> part.trim().split(Regex("\\s+")).joinToString("-") { w -> w.replaceFirstChar(Char::uppercaseChar) } }

    private fun raw(repo: String, path: String) = "$rawBase/$repo/main/$path"

    suspend fun check(make: String?, model: String?, year: Int?): SignalsetResult = withContext(Dispatchers.IO) {
        val missing = buildList {
            if (make.isNullOrBlank()) add(ProfileField.MAKE)
            if (model.isNullOrBlank()) add(ProfileField.MODEL)
            if (year == null) add(ProfileField.YEAR)
        }
        if (missing.isNotEmpty()) return@withContext SignalsetResult.NeedsProfile(missing)
        val repo = repoName(make!!, model!!)
        val generationsYaml = runCatching { text(raw(repo, "generations.yaml")) }.getOrNull()
        if (generationsYaml == null && http.status(raw(repo, "signalsets/v3/default.json")) == 404) {
            return@withContext SignalsetResult.NoRepository(repo)
        }
        val generations = generationsYaml?.let(GenerationsParser::parse).orEmpty()
        val generation = generations.firstOrNull { it.contains(year!!) }
        val probe = generation?.let { g -> (g.startYear..(g.endYear ?: currentYear())).toList() } ?: listOf(year!!)
        // years closest to the car's year first: the answer is usually found within 1–2 requests
        val hit = probe.sortedBy { kotlin.math.abs(it - year!!) }.firstOrNull { y -> http.status(raw(repo, "tests/test_cases/$y/command_support.yaml")) == 200 }
        if (hit == null) return@withContext SignalsetResult.NotCovered(repo, year!!, generation, emptyList())
        val json = text(raw(repo, "signalsets/v3/default.json"))
        val signalset = SignalsetParser.parse(json)
        val version = sha(json).take(12)
        save(repo, version, json, generationsYaml)
        SignalsetResult.Covered(repo, year!!, generation, version, signalset.ecus, signalset.forYear(year))
    }

    /** Already downloaded model signalset (offline). */
    suspend fun stored(make: String, model: String, year: Int): SignalsetResult.Covered? = withContext(Dispatchers.IO) {
        val repo = repoName(make, model)
        val installed = store.installed(packId(repo)) ?: return@withContext null
        val signalset = SignalsetParser.parse(installed.file(DEFAULT_JSON).readText())
        val generation = installed.file(GENERATIONS).takeIf { it.isFile }?.readText()?.let(GenerationsParser::parse)?.firstOrNull { it.contains(year) }
        SignalsetResult.Covered(repo, year, generation, installed.version, signalset.ecus, signalset.forYear(year))
    }

    private fun save(repo: String, version: String, json: String, generations: String?) {
        val id = packId(repo)
        val staging = store.staging(id, version)
        File(staging, DEFAULT_JSON).writeText(json)
        generations?.let { File(staging, GENERATIONS).writeText(it) }
        store.commit(id, version, staging)
    }

    private fun text(url: String): String = http.get(url).use { r ->
        if (r.code !in 200..299) throw IOException("HTTP ${r.code}")
        r.body.bufferedReader().readText()
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_JSON = "default.json"
        const val GENERATIONS = "generations.yaml"
        fun packId(repo: String) = "obdb-" + repo.substringAfter('/')
    }
}
