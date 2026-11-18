package io.github.iampopovich.eculense.core.obdb

import io.github.iampopovich.eculense.core.datapacks.PackHttp
import io.github.iampopovich.eculense.core.datapacks.PackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Результат проверки OBDb для машины пользователя. */
sealed interface SignalsetResult {
    /** В профиле нет марки/модели/года — искать нечего. */
    data class NeedsProfile(val missing: List<String>) : SignalsetResult

    /** Репозитория `OBDb/<Make>-<Model>` нет. */
    data class NoRepository(val repo: String) : SignalsetResult

    /** Репозиторий есть, но для поколения этого года нет данных с реальных машин (Kia Sorento BL 2002–2009). */
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
 * OBDb (github.com/OBDb, CC BY-SA 4.0): репозиторий на модель, `signalsets/v3/default.json` + `generations.yaml`.
 * Покрытие года = есть ли в репозитории тестовые данные (`tests/test_cases/<год>/command_support.yaml`) хоть для одного
 * года того же поколения; сами команды фильтруются по `filter` для года машины.
 * Только скачивание и просмотр: опрос расширенных PID требует ATH1/ATSH и сервисов 21/22 (бэклог 1.24 п. 6–7).
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
            if (make.isNullOrBlank()) add("марка")
            if (model.isNullOrBlank()) add("модель")
            if (year == null) add("год")
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
        // ближайшие к году машины — первыми: обычно ответ находится за 1–2 запроса
        val hit = probe.sortedBy { kotlin.math.abs(it - year!!) }.firstOrNull { y -> http.status(raw(repo, "tests/test_cases/$y/command_support.yaml")) == 200 }
        if (hit == null) return@withContext SignalsetResult.NotCovered(repo, year!!, generation, emptyList())
        val json = text(raw(repo, "signalsets/v3/default.json"))
        val signalset = SignalsetParser.parse(json)
        val version = sha(json).take(12)
        save(repo, version, json, generationsYaml)
        SignalsetResult.Covered(repo, year!!, generation, version, signalset.ecus, signalset.forYear(year))
    }

    /** Уже скачанный сигналсет модели (офлайн). */
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
