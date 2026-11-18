package io.github.iampopovich.eculense.core.dtcdb

import io.github.iampopovich.eculense.core.datapacks.PackDb

/** Заводские расшифровки (Wal33D/dtc-database, таблица dtc_definitions). */
fun interface OemDtcSource {
    fun lookup(code: String, manufacturer: String): String?
}

class Wal33dOemDtc(private val db: PackDb) : OemDtcSource, AutoCloseable {
    /** Марки в базе (KIA, CHEVY, MERCEDES…) без служебных GENERIC/OTHER. */
    val manufacturers: Set<String> by lazy {
        db.query("SELECT DISTINCT manufacturer FROM dtc_definitions") { it.string(0) }.filterNotNull()
            .filter { it !in OemBrands.NOT_A_BRAND }.toSet()
    }

    override fun lookup(code: String, manufacturer: String): String? =
        db.query(
            "SELECT description FROM dtc_definitions WHERE code = ? AND manufacturer = ? AND locale = 'en' LIMIT 1",
            listOf(code, manufacturer),
        ) { it.string(0) }.firstOrNull()?.trim()?.ifBlank { null }

    override fun close() = db.close()
}

/** Марка из профиля машины → марка в базе заводских кодов. Без регистра, с синонимами и русскими названиями. */
object OemBrands {
    /** «OTHER» в базе — смесь кодов разных марок (P1847 там — раздатка), это не марка машины. */
    val NOT_A_BRAND = setOf("GENERIC", "OTHER")

    private val ALIASES = mapOf(
        "CHEVROLET" to "CHEVY", "ШЕВРОЛЕ" to "CHEVY",
        "MERCEDESBENZ" to "MERCEDES", "MERCEDESAMG" to "MERCEDES", "МЕРСЕДЕС" to "MERCEDES", "МЕРСЕДЕСБЕНЦ" to "MERCEDES",
        "VW" to "VOLKSWAGEN", "ФОЛЬКСВАГЕН" to "VOLKSWAGEN",
        "КИА" to "KIA", "KIAMOTORS" to "KIA",
        "ТОЙОТА" to "TOYOTA", "ЛЕКСУС" to "LEXUS", "НИССАН" to "NISSAN", "ИНФИНИТИ" to "INFINITI",
        "ХОНДА" to "HONDA", "АКУРА" to "ACURA", "МАЗДА" to "MAZDA", "СУБАРУ" to "SUBARU", "СУЗУКИ" to "SUZUKI",
        "МИЦУБИСИ" to "MITSUBISHI", "МИТСУБИСИ" to "MITSUBISHI", "ФОРД" to "FORD", "АУДИ" to "AUDI", "БМВ" to "BMW",
        "ЯГУАР" to "JAGUAR", "ДЖИП" to "JEEP", "ДОДЖ" to "DODGE", "КРАЙСЛЕР" to "CHRYSLER", "КАДИЛЛАК" to "CADILLAC",
    )

    /** null — марка не указана или её нет в базе: тогда заводские коды не расшифровываем. */
    fun manufacturer(brand: String?, known: Set<String>): String? {
        val key = brand?.uppercase()?.filter { it.isLetterOrDigit() }?.ifBlank { null } ?: return null
        val m = ALIASES[key] ?: key
        return m.takeIf { it in known && it !in NOT_A_BRAND }
    }
}
