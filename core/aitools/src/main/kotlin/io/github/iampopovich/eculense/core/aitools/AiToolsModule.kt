package io.github.iampopovich.eculense.core.aitools

import ai.koog.agents.core.tools.ToolRegistry
import io.github.iampopovich.eculense.core.ai.VehicleToolbox
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.MaintenanceRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcDescriptions
import org.koin.dsl.module

/** Tool set for one dialogue turn; all read-only. */
internal class VehicleDataToolbox(private val ctx: ToolContext) : VehicleToolbox {
    constructor(
        analytics: AnalyticsRepository,
        cars: CarRepository,
        trips: TripRepository,
        dtc: DtcDescriptions?,
        maintenance: MaintenanceRepository? = null,
    ) : this(ToolContext(analytics, cars, trips, describeDtc = { codes, make -> dtc?.describeAll(codes, make).orEmpty() }, maintenance = maintenance))

    override fun registry(): ToolRegistry = ToolRegistry {
        tool(CarProfileTool(ctx))
        tool(TripStatsTool(ctx))
        tool(ListSessionsTool(ctx))
        tool(SessionStatsTool(ctx))
        tool(SensorSummaryTool(ctx))
        tool(FindingsTool(ctx))
        tool(DtcHistoryTool(ctx))
        tool(MaintenanceDueTool(ctx))
        tool(TrendsTool(ctx))
    }

    /** The on-device model does not call tools: the same information as plain text (no JSON — it used to just paraphrase it). */
    override suspend fun digest(): String? = digest(DEFAULT_SECTIONS, DEFAULT_DAYS)

    /**
     * Digest of only the [sections] sections (tool names, in this order); [days] — period for trends and trips.
     * A section that couldn't be built is skipped.
     */
    override suspend fun digest(sections: List<String>, days: Int): String? {
        val parts = sections.distinct().mapNotNull { name ->
            runCatching {
                when (name) {
                    "carProfile" -> DigestText.section(name, CarProfileTool(ctx).json(NoArgs()))
                    TrendsTool.NAME -> DigestText.trends(TrendsTool(ctx).json(TrendsArgs(days)))
                    "tripStats" -> DigestText.section(name, TripStatsTool(ctx).json(PeriodArgs(days = days)))
                    "dtcHistory" -> DigestText.section(name, DtcHistoryTool(ctx).json(LimitArgs(15)))
                    "findings" -> DigestText.section(name, FindingsTool(ctx).json(LimitArgs(8)))
                    "maintenanceDue" -> DigestText.section(name, MaintenanceDueTool(ctx).json(NoArgs()))
                    "listSessions" -> DigestText.section(name, ListSessionsTool(ctx).json(ListSessionsArgs(limit = 1)))
                    else -> null
                }
            }.getOrNull()
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    companion object {
        /** Digest without question routing: profile, six-month trends, DTCs, findings, maintenance, latest recording. */
        val DEFAULT_SECTIONS = listOf("carProfile", TrendsTool.NAME, "dtcHistory", "findings", "maintenanceDue", "listSessions")
        const val DEFAULT_DAYS = 180
    }
}

/** Wiring: one line in AppModules. Without this module the chat works, but without vehicle data. */
val aiToolsModule = module {
    single<VehicleToolbox> { VehicleDataToolbox(get(), get(), get(), getOrNull(), getOrNull()) }
}
