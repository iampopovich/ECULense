package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.ModuleScanDao
import io.github.iampopovich.eculense.core.database.ModuleScanEntity
import io.github.iampopovich.eculense.core.obd.ModuleDtc
import io.github.iampopovich.eculense.core.obd.ModuleDtcRead
import io.github.iampopovich.eculense.core.obd.ModuleDtcService
import io.github.iampopovich.eculense.core.obd.ModuleDtcStatus
import io.github.iampopovich.eculense.core.obd.ModuleKind
import io.github.iampopovich.eculense.core.obd.ModuleScanResult

/** One module of a stored scan. [address] = EcuAddress.label («7D1/7D9», «0x10»). */
data class StoredModule(val address: String, val kind: ModuleKind, val read: ModuleDtcRead)

data class StoredModuleScan(val atMs: Long, val protocol: Char, val modules: List<StoredModule>)

/** R-D: module scans per car. Live results of simulated links (demo, replay, PC emulator) are never stored. */
interface ModuleScanRepository {
    /** Stores [result] for the active car; false when the link is simulated, there is no car or nothing was found. */
    suspend fun save(result: ModuleScanResult): Boolean

    /** The newest stored scan of [carId] (default: the active car); null = none. */
    suspend fun latest(carId: Long? = null): StoredModuleScan?
}

internal class RoomModuleScanRepository(
    private val dao: ModuleScanDao,
    private val activeCarId: suspend () -> Long?,
    private val liveWrites: LiveWriteGate,
    private val clock: () -> Long = System::currentTimeMillis,
) : ModuleScanRepository {
    override suspend fun save(result: ModuleScanResult): Boolean {
        if (!liveWrites.allowed() || result.modules.isEmpty()) return false
        val car = activeCarId() ?: return false
        val at = clock()
        dao.insert(result.modules.map { r -> ModuleScanCodec.entity(car, at, result.protocol, StoredModule(r.module.address.label, r.module.kind, r.read)) })
        return true
    }

    override suspend fun latest(carId: Long?): StoredModuleScan? {
        val car = carId ?: activeCarId() ?: return null
        val rows = dao.latest(car)
        if (rows.isEmpty()) return null
        return StoredModuleScan(rows.first().scanAtMs, rows.first().protocol.firstOrNull() ?: '0', rows.map(ModuleScanCodec::module))
    }
}

/** Row text of module scans: codes as «code:flags;…» (T test failed, P pending, C confirmed, W warning lamp). */
internal object ModuleScanCodec {
    private val flags = mapOf(ModuleDtcStatus.TEST_FAILED to 'T', ModuleDtcStatus.PENDING to 'P', ModuleDtcStatus.CONFIRMED to 'C', ModuleDtcStatus.WARNING_LAMP to 'W')

    fun encodeDtcs(dtcs: List<ModuleDtc>): String = dtcs.joinToString(";") { d ->
        d.code + ":" + ModuleDtcStatus.entries.filter { it in d.status }.map { flags.getValue(it) }.joinToString("")
    }

    fun decodeDtcs(text: String): List<ModuleDtc> = text.split(';').filter { it.isNotBlank() }.mapNotNull { part ->
        val code = part.substringBefore(':').takeIf { it.length == 5 } ?: return@mapNotNull null
        val status = part.substringAfter(':', "").mapNotNull { c -> flags.entries.firstOrNull { it.value == c }?.key }.toSet()
        ModuleDtc(code, status)
    }

    fun entity(carId: Long, atMs: Long, protocol: Char, m: StoredModule): ModuleScanEntity {
        val (result, detail, dtcs) = when (val r = m.read) {
            is ModuleDtcRead.Codes -> Triple("CODES", r.service.name, encodeDtcs(r.dtcs))
            ModuleDtcRead.NotSupported -> Triple("NOT_SUPPORTED", null, "")
            ModuleDtcRead.NoReply -> Triple("NO_REPLY", null, "")
            is ModuleDtcRead.Refused -> Triple("REFUSED", "%02X".format(r.nrc), "")
        }
        return ModuleScanEntity(carId = carId, scanAtMs = atMs, protocol = protocol.toString(), address = m.address, moduleKind = m.kind.name, result = result, detail = detail, dtcs = dtcs)
    }

    fun module(e: ModuleScanEntity): StoredModule {
        val kind = runCatching { ModuleKind.valueOf(e.moduleKind) }.getOrDefault(ModuleKind.UNKNOWN)
        val read = when (e.result) {
            "CODES" -> ModuleDtcRead.Codes(
                e.detail?.let { runCatching { ModuleDtcService.valueOf(it) }.getOrNull() } ?: ModuleDtcService.MODE_03,
                decodeDtcs(e.dtcs),
            )
            "NOT_SUPPORTED" -> ModuleDtcRead.NotSupported
            "REFUSED" -> ModuleDtcRead.Refused(e.detail?.toIntOrNull(16) ?: 0)
            else -> ModuleDtcRead.NoReply
        }
        return StoredModule(e.address, kind, read)
    }
}
