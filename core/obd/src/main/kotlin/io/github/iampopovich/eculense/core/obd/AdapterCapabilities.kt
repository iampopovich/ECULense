package io.github.iampopovich.eculense.core.obd

/** What kind of adapter answered (a guess from its identification strings). */
enum class AdapterKind { GENUINE_ELM, CLONE, STN, VLINKER, UNKNOWN }

/**
 * Adapter identification (A6) from ATI, AT@1, STI/STDI and VTI. [unsupported] = optional commands the adapter
 * answered with '?' (or not at all); they are not sent to it again.
 */
data class AdapterCapabilities(
    val version: String = "",
    val description: String? = null,
    val stnId: String? = null,
    val deviceId: String? = null,
    val vlinkerId: String? = null,
    val kind: AdapterKind = AdapterKind.UNKNOWN,
    val unsupported: Set<String> = emptySet(),
) {
    companion object {
        val NONE = AdapterCapabilities()

        // Official ELM327 firmware versions; clones mostly claim the versions in CLONE_VERSIONS (freediag list).
        private val GENUINE_VERSIONS = setOf("1.0", "1.0A", "1.1", "1.2", "1.2A", "1.3", "1.3A", "1.4B", "2.0", "2.2", "2.3")
        private val CLONE_VERSIONS = setOf("1.4A", "1.4", "1.5A", "1.5", "2.1")
        private val versionNumber = Regex("[vV]\\s?([0-9]\\.[0-9][A-Za-z]?)")

        /** "1.5" from "ELM327 v1.5"; null when there is no version number. */
        fun versionNumber(version: String): String? = versionNumber.find(version)?.groupValues?.get(1)?.uppercase()

        fun kindOf(version: String, stnId: String?, vlinkerId: String?): AdapterKind = when {
            stnId != null -> AdapterKind.STN
            vlinkerId != null -> AdapterKind.VLINKER
            else -> when (versionNumber(version)) {
                null -> AdapterKind.UNKNOWN
                in CLONE_VERSIONS -> AdapterKind.CLONE
                in GENUINE_VERSIONS -> AdapterKind.GENUINE_ELM
                else -> AdapterKind.UNKNOWN
            }
        }
    }
}
