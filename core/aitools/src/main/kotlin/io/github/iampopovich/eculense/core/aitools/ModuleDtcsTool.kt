package io.github.iampopovich.eculense.core.aitools

import ai.koog.serialization.typeToken
import io.github.iampopovich.eculense.core.obd.ModuleDtcRead
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * R-D: the latest «Vehicle modules» scan of the active car (real links only): per module its kind, address and the
 * codes with status flags. English only (LLM-facing).
 */
internal class ModuleDtcsTool(ctx: ToolContext) : VehicleTool<NoArgs>(
    typeToken<NoArgs>(), NAME,
    "Latest scan of the car's modules (engine, transmission, ABS/ESC, body, cluster...) made on the Vehicle modules screen: " +
        "date, protocol and per module its address, and either its trouble codes with status flags (confirmed, pending, " +
        "test_failed = failing now, warning_lamp) or why none were read (not_supported, no_reply, refused). Airbag (SRS) " +
        "modules are never queried by the app. Codes of non-engine modules are often manufacturer-specific: if the title " +
        "is unknown, say so instead of guessing. ECULense does not clear codes of non-engine modules.",
    ctx,
) {
    override suspend fun answer(args: NoArgs, carId: Long): JsonObject {
        val repo = ctx.moduleScans ?: return Compact.noData("module scans are not available in this build")
        val scan = repo.latest(carId) ?: return Compact.noData("no module scan stored yet: the user can run one in Pit stop > Vehicle modules with the car connected")
        val zone = ctx.zone()
        val codes = scan.modules.flatMap { m -> (m.read as? ModuleDtcRead.Codes)?.dtcs.orEmpty().map { it.code } }.distinct()
        val make = ctx.cars.cars().firstOrNull { it.id == carId }?.brand
        val info = runCatching { ctx.describeDtc(codes, make) }.getOrDefault(emptyMap())
        return buildJsonObject {
            put("date", Dates.dateTime(scan.atMs, zone))
            put("protocol", scan.protocol.toString())
            put("modules", JsonArray(scan.modules.map { m ->
                buildJsonObject {
                    put("module", m.kind.name.lowercase())
                    put("address", m.address)
                    when (val r = m.read) {
                        is ModuleDtcRead.Codes -> {
                            put("read_with", r.service.name.lowercase())
                            put("codes", JsonArray(r.dtcs.map { d ->
                                val flags = d.status.joinToString(",") { it.name.lowercase() }
                                val title = info[d.code]?.title
                                JsonPrimitive(listOfNotNull(d.code, flags.ifEmpty { null }, title).joinToString(" · "))
                            }))
                        }
                        ModuleDtcRead.NotSupported -> put("status", "not_supported")
                        ModuleDtcRead.NoReply -> put("status", "no_reply")
                        is ModuleDtcRead.Refused -> put("status", "refused 7F %02X".format(r.nrc))
                    }
                }
            }))
        }
    }

    companion object {
        const val NAME = "moduleDtcs"
    }
}
