package io.github.iampopovich.eculense.core.aitools

import ai.koog.agents.core.tools.ToolRegistry
import io.github.iampopovich.eculense.core.ai.VehicleToolbox
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcDescriptions
import org.koin.dsl.module

/** Набор инструментов на один ход диалога; все только на чтение. */
internal class VehicleDataToolbox(private val ctx: ToolContext) : VehicleToolbox {
    constructor(analytics: AnalyticsRepository, cars: CarRepository, trips: TripRepository, dtc: DtcDescriptions?) :
        this(ToolContext(analytics, cars, trips, describeDtc = { codes, make -> dtc?.describeAll(codes, make).orEmpty() }))

    override fun registry(): ToolRegistry = ToolRegistry {
        tool(CarProfileTool(ctx))
        tool(TripStatsTool(ctx))
        tool(ListSessionsTool(ctx))
        tool(SessionStatsTool(ctx))
        tool(SensorSummaryTool(ctx))
        tool(FindingsTool(ctx))
        tool(DtcHistoryTool(ctx))
    }

    override fun progressLabel(toolName: String): String = LABELS[toolName] ?: "ИИ смотрит данные машины…"

    companion object {
        val LABELS = mapOf(
            "carProfile" to "ИИ смотрит профиль машины…",
            "tripStats" to "ИИ смотрит статистику поездок…",
            "listSessions" to "ИИ смотрит список записей…",
            "sessionStats" to "ИИ смотрит запись поездки…",
            "sensorSummary" to "ИИ смотрит показания датчика…",
            "findings" to "ИИ смотрит находки анализа…",
            "dtcHistory" to "ИИ смотрит ошибки…",
        )
    }
}

/** Подключение: строка в AppModules. Без этого модуля чат работает, но без данных машины. */
val aiToolsModule = module {
    single<VehicleToolbox> { VehicleDataToolbox(get(), get(), get(), getOrNull()) }
}
