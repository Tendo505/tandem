package io.github.tendo505.tandem.app.sync

import io.github.tendo505.tandem.app.data.TandemDao
import io.github.tendo505.tandem.core.HlcClock
import io.github.tendo505.tandem.core.Limits
import io.github.tendo505.tandem.core.PullResponse
import io.github.tendo505.tandem.core.PushRequest
import io.github.tendo505.tandem.core.PushResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** The server API, as the app sees it. Network failures are [IOException]s; refusals are [SyncRejectedException]s. */
interface SyncApi {
    suspend fun push(listId: String, request: PushRequest): PushResponse
    suspend fun pull(listId: String, after: Long, limit: Int): PullResponse
}

class SyncRejectedException(message: String) : Exception(message)

enum class SyncProblem { NOT_CONFIGURED, OFFLINE, REJECTED }

data class SyncStatus(
    val running: Boolean = false,
    val lastSuccessAt: Long? = null,
    val problem: SyncProblem? = null,
    val detail: String? = null,
)

/**
 * Moves ops between the local database and the server: upload the outbox,
 * then read the list's log from the saved cursor. Both steps are safe to
 * interrupt at any point (pushes are idempotent, and each pulled page is
 * applied together with its cursor), so syncs can be cancelled or retried
 * freely.
 */
class SyncEngine(
    private val dao: TandemDao,
    private val clock: HlcClock,
    private val api: () -> SyncApi?,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pageSize: Int = 500,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun syncAll(): SyncProblem? = sync(dao.lists().map { it.id })

    /** Sync the given lists. Returns null on success, otherwise what went wrong. */
    suspend fun sync(listIds: List<String>): SyncProblem? = mutex.withLock {
        val api = api() ?: return fail(SyncProblem.NOT_CONFIGURED, null)
        _status.update { it.copy(running = true) }
        withContext(io) {
            try {
                for (id in listIds) {
                    push(api, id)
                    pull(api, id)
                }
                _status.value = SyncStatus(lastSuccessAt = now())
                null
            } catch (e: IOException) {
                fail(SyncProblem.OFFLINE, e.message)
            } catch (e: SyncRejectedException) {
                fail(SyncProblem.REJECTED, e.message)
            } finally {
                _status.update { it.copy(running = false) } // also when cancelled
            }
        }
    }

    private fun fail(problem: SyncProblem, detail: String?): SyncProblem {
        _status.update { it.copy(running = false, problem = problem, detail = detail) }
        return problem
    }

    private suspend fun push(api: SyncApi, listId: String) {
        while (true) {
            val batch = dao.outbox(listId, Limits.MAX_OPS_PER_PUSH)
            if (batch.isEmpty()) return
            api.push(listId, PushRequest(batch.map { it.toOp() }))
            dao.deleteSent(listId, batch.map { it.hlc })
        }
    }

    private suspend fun pull(api: SyncApi, listId: String) {
        var cursor = dao.list(listId)?.cursor ?: return
        while (true) {
            val page = api.pull(listId, cursor, pageSize)
            // Later local edits must outrank everything we've seen, even if
            // another phone's clock runs ahead of ours.
            page.ops.forEach { clock.observe(it.op.timestamp) }
            dao.receivePage(listId, page.ops)
            // The cursor only ever moves to the last op actually received; see PullResponse.
            cursor = page.ops.lastOrNull()?.seq ?: cursor
            if (!page.hasMore || page.ops.isEmpty()) return
        }
    }
}
