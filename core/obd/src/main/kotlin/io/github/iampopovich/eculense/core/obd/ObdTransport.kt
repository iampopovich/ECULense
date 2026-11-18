package io.github.iampopovich.eculense.core.obd

/** Канал до адаптера. Один экземпляр = одно подключение (после close() не переиспользуется). */
interface ObdTransport {
    suspend fun open()
    suspend fun write(data: ByteArray)
    /** Ждёт, пока придёт хотя бы один байт. Бросает IOException, если связь потеряна. */
    suspend fun read(): ByteArray
    suspend fun close()
}

/** Адаптер, к которому можно подключиться. kind — какой фабрикой создавать транспорт. */
data class AdapterEndpoint(val kind: String, val address: String, val label: String)

/** Каждый вид связи (BT, Wi-Fi, BLE) — отдельная фабрика, регистрируется в Koin через bind TransportFactory::class. */
interface TransportFactory {
    val kind: String
    suspend fun discover(): List<AdapterEndpoint>
    fun create(endpoint: AdapterEndpoint): ObdTransport
}
