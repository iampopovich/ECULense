package io.github.iampopovich.eculense.core.aitools

import ai.koog.agents.core.tools.ToolRegistry
import io.github.iampopovich.eculense.core.ai.VehicleToolbox
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.MaintenanceRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcDescriptions
import org.koin.dsl.module

/** Набор инструментов на один ход диалога; все только на чтение. */
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

    override fun progressLabel(toolName: String): String = LABELS[toolName] ?: "ИИ смотрит данные машины…"

    /** Модель на телефоне инструменты не вызывает: та же информация обычным текстом (без JSON — его она пересказывала). */
    override suspend fun digest(): String? = digest(DEFAULT_SECTIONS, DEFAULT_DAYS)

    /**
     * Сводка только из разделов [sections] (имена инструментов, в этом порядке); [days] — период трендов и поездок.
     * Раздел, который не удалось собрать, пропускается.
     */
    internal suspend fun digest(sections: List<String>, days: Int): String? {
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
        /** Сводка без маршрутизации вопроса: профиль, тренды за полгода, ошибки, находки, ТО, последняя запись. */
        val DEFAULT_SECTIONS = listOf("carProfile", TrendsTool.NAME, "dtcHistory", "findings", "maintenanceDue", "listSessions")
        const val DEFAULT_DAYS = 180

        val LABELS = mapOf(
            "carProfile" to "ИИ смотрит профиль машины…",
            "tripStats" to "ИИ смотрит статистику поездок…",
            "listSessions" to "ИИ смотрит список записей…",
            "sessionStats" to "ИИ смотрит запись поездки…",
            "sensorSummary" to "ИИ смотрит показания датчика…",
            "findings" to "ИИ смотрит находки анализа…",
            "dtcHistory" to "ИИ смотрит ошибки…",
            "maintenanceDue" to "ИИ смотрит план ТО…",
            TrendsTool.NAME to "ИИ смотрит тренды по месяцам…",
        )
    }
}

/** Подключение: строка в AppModules. Без этого модуля чат работает, но без данных машины. */
val aiToolsModule = module {
    single<VehicleToolbox> { VehicleDataToolbox(get(), get(), get(), getOrNull(), getOrNull()) }
}
