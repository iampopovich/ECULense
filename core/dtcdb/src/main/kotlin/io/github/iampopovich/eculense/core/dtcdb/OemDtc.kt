package io.github.iampopovich.eculense.core.dtcdb

import io.github.iampopovich.eculense.core.datapacks.PackDb

/** Manufacturer descriptions (Wal33D/dtc-database, dtc_definitions table). */
fun interface OemDtcSource {
    fun lookup(code: String, manufacturer: String): String?
}

class Wal33dOemDtc(private val db: PackDb) : OemDtcSource, AutoCloseable {
    /** Makes in the database (KIA, CHEVY, MERCEDES…) without the service GENERIC/OTHER. */
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

/** Make from the car profile → make in the manufacturer code database. Case-insensitive, with synonyms and Russian names. */
object OemBrands {
    /** «OTHER» in the database is a mix of codes from different makes (P1847 there is the transfer case), not a car make. */
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

    /** null — make not set or not in the database: then manufacturer codes are not decoded. */
    fun manufacturer(brand: String?, known: Set<String>): String? {
        val key = brand?.uppercase()?.filter { it.isLetterOrDigit() }?.ifBlank { null } ?: return null
        val m = ALIASES[key] ?: key
        return m.takeIf { it in known && it !in NOT_A_BRAND }
    }
}
