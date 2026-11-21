package io.github.iampopovich.eculense.core.mqtt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home Assistant device discovery payload against the field rules of the HA MQTT docs. */
class HaDiscoveryTest {
    private val sensors = listOf(
        HaDiscovery.Sensor(HaDiscovery.key("01.0C"), "Engine speed", "rpm"),
        HaDiscovery.Sensor(HaDiscovery.key("01.05"), "Coolant temperature", "°C"),
        HaDiscovery.Sensor(HaDiscovery.key("01.0D"), "Vehicle speed", "km/h"),
        HaDiscovery.Sensor("battery_voltage", "Battery voltage", "V"),
        HaDiscovery.Sensor("connection", "Connection", null, numeric = false),
        HaDiscovery.Sensor(HaDiscovery.key("obdb:NIROEV_HVBAT_SOC"), "State of charge", "%"),
    )

    @Test
    fun `node id and keys use only allowed characters, never personal data`() {
        assertEquals("eculense_a1b2c3_c4", HaDiscovery.nodeId("a1b2-c3!", 4))
        assertEquals("eculense_a1b2c3", HaDiscovery.nodeId("a1b2c3", null))
        assertTrue(Regex("[a-zA-Z0-9_-]+").matches(HaDiscovery.nodeId("Ünïcode id", 1)))
        assertEquals("pid_0c", HaDiscovery.key("01.0C"))
        assertEquals("obdb_niroev_hvbat_soc", HaDiscovery.key("obdb:NIROEV_HVBAT_SOC"))
        assertEquals("v_12x", HaDiscovery.key("12x"))
        assertEquals("Kia Sorento 2005", HaDiscovery.carName("Kia", "Sorento", 2005))
        assertEquals("ECULense car", HaDiscovery.carName(null, " ", null))
        assertEquals("eculense", HaDiscovery.cleanPrefix(" /#/ "))
        assertEquals("home/car", HaDiscovery.cleanPrefix("/home/car/"))
    }

    @Test
    fun `device config has dev, o, cmps with p and unique_id, shared state and availability topics`() {
        val node = HaDiscovery.nodeId("abc123", 1)
        val json = Json.parseToJsonElement(HaDiscovery.config(node, "Kia Sorento 2005", "0.2.0", "eculense", sensors)).jsonObject
        assertEquals("homeassistant/device/eculense_abc123_c1/config", HaDiscovery.configTopic(node))
        val dev = json.getValue("dev").jsonObject
        assertEquals("Kia Sorento 2005", dev.getValue("name").jsonPrimitive.content)
        assertEquals(node, (dev.getValue("ids") as kotlinx.serialization.json.JsonArray)[0].jsonPrimitive.content)
        assertEquals("ECULense", json.getValue("o").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("eculense/eculense_abc123_c1/state", json.getValue("state_topic").jsonPrimitive.content)
        assertEquals("eculense/status", json.getValue("availability_topic").jsonPrimitive.content)
        val cmps = json.getValue("cmps").jsonObject
        assertEquals(sensors.size, cmps.size)
        for ((id, c) in cmps) {
            val o = c as JsonObject
            assertEquals("sensor", o.getValue("p").jsonPrimitive.content)
            assertEquals("${node}_$id", o.getValue("unique_id").jsonPrimitive.content)
            assertEquals("{{ value_json.$id }}", o.getValue("value_template").jsonPrimitive.content)
        }
        fun cls(id: String) = cmps.getValue(id).jsonObject["device_class"]?.jsonPrimitive?.content
        assertEquals("temperature", cls("pid_05"))
        assertEquals("speed", cls("pid_0d"))
        assertEquals("voltage", cls("battery_voltage"))
        assertNull(cls("pid_0c")) // rpm has no HA device class
        assertNull(cls("obdb_niroev_hvbat_soc")) // % alone is not a device class (battery would be a guess)
        val connection = cmps.getValue("connection").jsonObject
        assertFalse("unit_of_measurement" in connection)
        assertFalse("state_class" in connection)
        assertEquals("°C", cmps.getValue("pid_05").jsonObject.getValue("unit_of_measurement").jsonPrimitive.content)
        assertEquals("measurement", cmps.getValue("pid_0c").jsonObject.getValue("state_class").jsonPrimitive.content)
    }

    @Test
    fun `state JSON keeps numbers numeric and drops missing values, removal is an empty retained payload`() {
        val s = Json.parseToJsonElement(HaDiscovery.state(mapOf("pid_0c" to 812.5, "connection" to "connected", "dtc_count" to 2, "pid_05" to null, "x" to Double.NaN))).jsonObject
        assertEquals(812.5, s.getValue("pid_0c").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("connected", s.getValue("connection").jsonPrimitive.content)
        assertEquals(2, s.getValue("dtc_count").jsonPrimitive.content.toInt())
        assertFalse("pid_05" in s)
        assertFalse("x" in s)
        val removal = HaDiscovery.removalMessage("n1")
        assertEquals(0, removal.payload.size)
        assertTrue(removal.retain)
        assertTrue(HaDiscovery.offlineMessage("p").retain)
    }
}
