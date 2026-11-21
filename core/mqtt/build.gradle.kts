plugins {
    id("ecu.jvm.library")
}

// Own publish-only MQTT 3.1.1 client (CONNECT/CONNACK, PUBLISH QoS 0/1 + PUBACK, PINGREQ/PINGRESP, DISCONNECT, LWT,
// TLS via the platform SSLSocketFactory) and the Home Assistant discovery payloads. Pure JVM: tested against a fake
// broker on a loopback ServerSocket. Removable together with :feature:mqtt.
dependencies {
    // LocalNetwork: plain TCP only to local-network hosts (the same rule as the AI «Other server» guard).
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
}
