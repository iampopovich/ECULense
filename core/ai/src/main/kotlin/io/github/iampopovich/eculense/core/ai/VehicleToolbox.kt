package io.github.iampopovich.eculense.core.ai

import ai.koog.agents.core.tools.ToolRegistry

/**
 * Vehicle data tools for the agent (implements :core:aitools; without it the chat works without data).
 * Read-only; responses are compact JSON without VIN or coordinates.
 */
interface VehicleToolbox {
    /** A fresh tool set per dialog turn (the active car is resolved at call time). */
    fun registry(): ToolRegistry

    /** Only tools whose names are in [only]: each expert has its own set. */
    fun registry(only: Set<String>): ToolRegistry = ToolRegistry { tools(registry().tools.filter { it.name in only }) }

    /**
     * Vehicle data digest as a single text (tool outputs for profile, DTCs, findings, maintenance and the latest recording), for
     * on-phone models that don't call tools: the numbers are still computed by the app. null means no data.
     */
    suspend fun digest(): String? = null

    /**
     * Digest of only the [sections] (tool names, in this order): data relevant to the question, not everything.
     * [days] is the period for trends and trips. Defaults to the full [digest].
     */
    suspend fun digest(sections: List<String>, days: Int): String? = digest()
}
