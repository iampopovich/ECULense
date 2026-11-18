package io.github.iampopovich.eculense.core.datapacks

/** Файл пакета. sha256/size известны только для зафиксированной версии; у обновления — null (проверяется длина ответа). */
data class PackFile(
    val name: String,
    val url: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

/** Откуда узнать последнюю версию пакета для еженедельной проверки обновлений. */
sealed interface UpdateSource {
    /** Нет проверки: версия меняется только с релизом приложения. */
    data object None : UpdateSource

    /**
     * Релизы GitHub: тег из редиректа `https://github.com/<repo>/releases/latest` (без REST API — он ограничен по IP).
     * files: имя файла → шаблон URL с `{tag}` (ассет релиза или raw-файл на теге, если ассетов нет).
     */
    data class GithubRelease(val repo: String, val files: Map<String, String>) : UpdateSource {
        fun fileUrl(tag: String, name: String) = files.getValue(name).replace("{tag}", tag)
    }

    /** Версия — строковое поле JSON (OBDex: `meta.json` → `generated_at`); URL файлов не меняется. */
    data class JsonField(val url: String, val field: String) : UpdateSource
}

/**
 * Описание пакета данных: что, откуда, какой версии, под какой лицензией. Версии зафиксированы в коде ([PackCatalog]);
 * обновление = новый тег/версия источника, файл берётся без изменений (для ODbL — без производной БД).
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
    /** Строка атрибуции для «О приложении». */
    val attribution: String,
    val update: UpdateSource = UpdateSource.None,
    /** Справочники показывает экран «Данные и справочники»; модели ИИ — свой экран (:core:ailocal). */
    val group: PackGroup = PackGroup.REFERENCE,
    /**
     * Многогигабайтные файлы: `.part` переживает обрыв/остановку и докачивается с места (HTTP Range), воркер работает
     * как foreground-задача и повторяет попытки. У справочников — false: при ошибке всё начинается заново.
     */
    val resumable: Boolean = false,
) {
    val sizeBytes: Long? get() = files.map { it.sizeBytes ?: return null }.sum()

    /** Файлы другой версии: у релизов GitHub URL зависит от тега, хэш новой версии неизвестен. */
    fun filesFor(version: String): List<PackFile> = when {
        version == this.version -> files
        update is UpdateSource.GithubRelease -> update.files.keys.map { PackFile(it, update.fileUrl(version, it)) }
        else -> files.map { it.copy(sizeBytes = null, sha256 = null) }
    }
}

enum class PackGroup { REFERENCE, MODEL }

/**
 * Заголовки авторизации для URL (токен Hugging Face для gated-моделей). Отдаются только исходному хосту запроса:
 * после редиректа на CDN токен не уходит. Токен не хранится в манифесте и не попадает в WorkManager.
 */
fun interface PackAuth {
    fun headersFor(url: String): Map<String, String>
}

/** Каталог пакетов, который меняется во время работы (модели пользователя по ссылке). */
fun interface PackSource {
    fun manifests(): List<PackManifest>
}

/** Имя каталога версии: теги и даты ISO содержат «:», которые в путях лучше не держать. */
internal fun versionDir(version: String): String = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
