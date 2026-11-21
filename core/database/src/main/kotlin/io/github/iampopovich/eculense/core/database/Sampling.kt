package io.github.iampopovich.eculense.core.database

/**
 * Keeps every [step]-th value by position in the stream it is fed (the values of ONE sensor, in time order), so the
 * sample of each sensor has ~count/step values no matter how sensors are interleaved in the readings table. The old
 * `readings.id % step` filter used the global row id: with 6 sensors polled round-robin and step 6 every kept id fell
 * on the same sensor and the other five got empty samples (R1.3).
 */
class Decimator(private val step: Int) {
    init {
        require(step >= 1) { "step must be >= 1" }
    }

    private var position = 0L
    private val kept = ArrayList<Double>()
    val values: List<Double> get() = kept

    fun offer(value: Double) {
        if (position++ % step == 0L) kept += value
    }

    companion object {
        fun of(values: Iterable<Double>, step: Int): List<Double> = Decimator(step).apply { values.forEach(::offer) }.values
    }
}

/** Rows read per page: memory stays bounded on 100k+ reading sessions. */
internal const val SAMPLE_PAGE = 4_096

/**
 * Feeds the values of [sensorId] in [sessionId] at or after [fromTs] to [sink] in (timestampMs, id) order, page by
 * page with keyset pagination over the (sessionId, sensorId, timestampMs) index.
 */
suspend fun AnalyticsDao.forEachValue(sessionId: Long, sensorId: String, fromTs: Long = Long.MIN_VALUE, sink: (Double) -> Unit) {
    var afterTs = fromTs
    var afterId = -1L
    while (true) {
        val page = valuesPage(sessionId, sensorId, afterTs, afterId, SAMPLE_PAGE)
        page.forEach { sink(it.value) }
        if (page.size < SAMPLE_PAGE) return
        val last = page.last()
        afterTs = last.timestampMs
        afterId = last.id
    }
}

/** Every [step]-th value of one sensor in one session (by position within the sensor). */
suspend fun AnalyticsDao.sessionSample(sessionId: Long, sensorId: String, step: Int): List<Double> {
    val d = Decimator(step)
    forEachValue(sessionId, sensorId) { d.offer(it) }
    return d.values
}

/**
 * Every [step]-th value of one sensor over the car's sessions started in [fromMs]..[toMs] (sessions in start order).
 * [warmFromC] non-null: only values from the first coolant reading >= [warmFromC] °C of each session on (warm engine);
 * sessions that never warmed up are skipped.
 */
suspend fun AnalyticsDao.sensorSample(carId: Long, sensorId: String, fromMs: Long, toMs: Long, step: Int, warmFromC: Double? = null): List<Double> {
    val d = Decimator(step)
    for (s in sessionStarts(carId)) {
        if (s.startedAtMs !in fromMs..toMs) continue
        val from = if (warmFromC != null) warmStart(s.id, warmFromC) ?: continue else Long.MIN_VALUE
        forEachValue(s.id, sensorId, from) { d.offer(it) }
    }
    return d.values
}
