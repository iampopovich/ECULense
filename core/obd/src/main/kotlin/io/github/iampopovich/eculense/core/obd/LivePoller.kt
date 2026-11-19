package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/** Endless round-robin polling. A failure of one PID (ObdException) does not stop the others. IOException is rethrown. */
internal class LivePoller(private val session: ObdSession, private val pauseBetweenCyclesMs: Long = 0) {
    fun poll(pids: List<Pid>): Flow<SensorReading> = flow {
        if (pids.isEmpty()) return@flow
        while (true) {
            for (pid in pids) {
                val reading = try {
                    session.read(pid)
                } catch (e: ObdException) {
                    null
                }
                if (reading != null) emit(reading)
            }
            if (pauseBetweenCyclesMs > 0) delay(pauseBetweenCyclesMs) else yield()
        }
    }
}
