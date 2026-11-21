package io.github.iampopovich.eculense.core.mqtt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Home Assistant MQTT discovery (https://www.home-assistant.io/integrations/mqtt/ — «Device discovery payload»,
 * checked 2026-11-21 in home-assistant.io `source/_integrations/mqtt.markdown`): ONE retained config per device in
 * `<discovery_prefix>/device/<object_id>/config` with the mandatory `dev` and `o` (origin) mappings, the components in
 * `cmps` (each with `p` platform and `unique_id`), shared `state_topic` and availability options at the root. An
 * empty retained payload on the same topic removes the device. `<object_id>` must match `[a-zA-Z0-9_-]`.
 *
 * Privacy: the node id comes from a random per-install id (+ the local car row number), never VIN, MAC or the car's
 * nickname; the device name is make + model + year only.
 */
object HaDiscovery {
    const val DISCOVERY_PREFIX = "homeassistant"
    const val ONLINE = "online"
    const val OFFLINE = "offline"
    const val ORIGIN_URL = "https://github.com/iampopovich/ECULense"

    /** One HA sensor of the device. [key] = field in the state JSON (`value_json.<key>`). */
    data class Sensor(val key: String, val name: String, val unit: String?, val numeric: Boolean = true) {
        /** HA sensor device classes only where unit and class are valid together (voltage, temperature, speed). */
        val deviceClass: String?
            get() = when (unit) {
                "V" -> "voltage"
                "°C" -> "temperature"
                "km/h" -> "speed"
                else -> null
            }
    }

    private val unsafe = Regex("[^a-zA-Z0-9]")
    private val keyUnsafe = Regex("[^a-z0-9_]")

    /** `eculense_<install>_c<car>`: only `[a-zA-Z0-9_-]`; [carSlot] = local car row id (null = no car chosen). */
    fun nodeId(installId: String, carSlot: Long?): String {
        val install = installId.replace(unsafe, "").take(32).ifEmpty { "install" }
        return "eculense_$install" + (carSlot?.let { "_c$it" } ?: "")
    }

    /** State JSON field for a sensor id: «01.0C» → «pid_0c», «obdb:NIROEV_SOC» → «obdb_niroev_soc». */
    fun key(sensorId: String): String {
        val raw = when {
            sensorId.startsWith("01.") -> "pid_" + sensorId.removePrefix("01.")
            sensorId.startsWith("obdb:") -> "obdb_" + sensorId.removePrefix("obdb:")
            else -> sensorId
        }.lowercase().replace(keyUnsafe, "_")
        return if (raw.firstOrNull()?.isLetter() == true) raw else "v_$raw"
    }

    fun configTopic(nodeId: String, discoveryPrefix: String = DISCOVERY_PREFIX) = "$discoveryPrefix/device/$nodeId/config"

    fun stateTopic(prefix: String, nodeId: String) = "${cleanPrefix(prefix)}/$nodeId/state"

    /** Availability for the whole install (Last Will «offline», retained). */
    fun statusTopic(prefix: String) = "${cleanPrefix(prefix)}/status"

    /** Topic prefix as the user typed it, without wildcards, leading/trailing slashes or spaces; default «eculense». */
    fun cleanPrefix(prefix: String): String =
        prefix.trim().trim('/').replace(Regex("[+#\\s]"), "").ifEmpty { "eculense" }

    /** Make + model + year only (no nickname, no VIN); «ECULense car» when the profile has none of them. */
    fun carName(make: String?, model: String?, year: Int?): String =
        listOfNotNull(make?.trim()?.takeIf { it.isNotEmpty() }, model?.trim()?.takeIf { it.isNotEmpty() }, year?.toString())
            .joinToString(" ").ifEmpty { "ECULense car" }

    fun config(nodeId: String, deviceName: String, appVersion: String, prefix: String, sensors: List<Sensor>): String {
        val json: JsonObject = buildJsonObject {
            put("dev", buildJsonObject {
                put("ids", buildJsonArray { add(JsonPrimitive(nodeId)) })
                put("name", deviceName)
                put("mf", "ECULense")
                put("sw", appVersion)
            })
            put("o", buildJsonObject {
                put("name", "ECULense")
                put("sw", appVersion)
                put("url", ORIGIN_URL)
            })
            put("cmps", buildJsonObject {
                for (s in sensors.distinctBy { it.key }) {
                    put(s.key, buildJsonObject {
                        put("p", "sensor")
                        put("name", s.name)
                        put("unique_id", "${nodeId}_${s.key}")
                        put("value_template", "{{ value_json.${s.key} }}")
                        if (s.numeric) {
                            s.unit?.takeIf { it.isNotEmpty() }?.let { put("unit_of_measurement", it) }
                            s.deviceClass?.let { put("device_class", it) }
                            put("state_class", "measurement")
                        }
                    })
                }
            })
            put("state_topic", stateTopic(prefix, nodeId))
            put("availability_topic", statusTopic(prefix))
            put("payload_available", ONLINE)
            put("payload_not_available", OFFLINE)
            put("qos", 0)
        }
        return json.toString()
    }

    /** State JSON: numbers as numbers, text as text, null fields left out (HA keeps the last value). */
    fun state(values: Map<String, Any?>): String = buildJsonObject {
        for ((k, v) in values) {
            when (v) {
                null -> Unit
                is Number -> if (v.toDouble().isFinite()) put(k, JsonPrimitive(v))
                is Boolean -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }.toString()

    /** Messages that (re)announce a device: retained config, QoS 1. */
    fun configMessage(nodeId: String, payload: String, discoveryPrefix: String = DISCOVERY_PREFIX) =
        MqttMessage(configTopic(nodeId, discoveryPrefix), payload, MqttQos.AT_LEAST_ONCE, retain = true)

    /** Removal of a device: empty retained payload on its config topic. */
    fun removalMessage(nodeId: String, discoveryPrefix: String = DISCOVERY_PREFIX) =
        MqttMessage(configTopic(nodeId, discoveryPrefix), ByteArray(0), MqttQos.AT_LEAST_ONCE, retain = true)

    fun onlineMessage(prefix: String) = MqttMessage(statusTopic(prefix), ONLINE, MqttQos.AT_LEAST_ONCE, retain = true)

    /** Last Will and the graceful «offline» before DISCONNECT. */
    fun offlineMessage(prefix: String) = MqttMessage(statusTopic(prefix), OFFLINE, MqttQos.AT_LEAST_ONCE, retain = true)
}
