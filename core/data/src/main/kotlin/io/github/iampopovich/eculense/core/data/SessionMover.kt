package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.SessionMoveDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * C8: «Reassign car» on a recording. The session, its derived LIVE trip ("session:<id>") and its events move to
 * [carId] in one transaction; then findings of both cars are recomputed (stats read trips live, so they follow).
 */
interface SessionMover {
    suspend fun carOf(sessionId: Long): Long?
    /** true = moved; false = it already belongs to [carId] or the recording is gone. */
    suspend fun move(sessionId: Long, carId: Long): Boolean
}

internal class RoomSessionMover(
    private val dao: SessionMoveDao,
    private val scope: CoroutineScope,
    private val recompute: suspend (carId: Long) -> Unit,
) : SessionMover {
    override suspend fun carOf(sessionId: Long): Long? = dao.carOf(sessionId)

    override suspend fun move(sessionId: Long, carId: Long): Boolean {
        val from = dao.carOf(sessionId)
        if (from == carId) return false
        dao.move(sessionId, carId)
        scope.launch {
            listOfNotNull(from, carId).distinct().forEach { runCatching { recompute(it) } }
        }
        return true
    }
}
