package io.github.tendo505.tandem.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tendo505.tandem.app.data.TandemDb
import io.github.tendo505.tandem.app.data.TandemRepository
import io.github.tendo505.tandem.app.sync.SyncApi
import io.github.tendo505.tandem.app.sync.SyncEngine
import io.github.tendo505.tandem.app.sync.SyncRejectedException
import io.github.tendo505.tandem.core.HlcClock
import io.github.tendo505.tandem.core.PullResponse
import io.github.tendo505.tandem.core.PushRequest
import io.github.tendo505.tandem.core.PushResponse
import io.github.tendo505.tandem.core.SeqOp
import java.io.IOException

/** Behaves like the real server: an ordered, de-duplicated log per list. Can be switched offline or made to refuse. Tests call it from one thread at a time. */
class FakeServer : SyncApi {
    private val log = mutableListOf<SeqOp>()
    var online = true
    var refuse: String? = null
    var pulls = 0

    override suspend fun push(listId: String, request: PushRequest): PushResponse {
        check()
        refuse?.let { throw SyncRejectedException(it) }
        var stored = 0
        for (op in request.ops) {
            if (log.none { it.op.listId == listId && it.op.hlc == op.hlc }) {
                log += SeqOp(log.size + 1L, op)
                stored++
            }
        }
        return PushResponse(stored, latest(listId))
    }

    override suspend fun pull(listId: String, after: Long, limit: Int): PullResponse {
        check()
        pulls++
        val ops = log.filter { it.op.listId == listId && it.seq > after }.take(limit + 1)
        return PullResponse(ops.take(limit), latest(listId), hasMore = ops.size > limit)
    }

    fun opCount(listId: String) = log.count { it.op.listId == listId }

    private fun latest(listId: String) = log.lastOrNull { it.op.listId == listId }?.seq ?: 0

    private fun check() {
        if (!online) throw IOException("no network")
    }
}

/** One phone: its own database, clock and sync engine. */
class Phone(name: String, server: SyncApi?, var wall: Long = 1_800_000_000_000, pageSize: Int = 500) {
    val db: TandemDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), TandemDb::class.java)
        .allowMainThreadQueries()
        .build()
    val clock = HlcClock(name, { wall })
    var syncRequests = 0
    val repo = TandemRepository(db.dao(), clock, onLocalChange = { syncRequests++ }, now = { wall })
    val engine = SyncEngine(db.dao(), clock, api = { server }, now = { wall }, pageSize = pageSize)

    /** Let a little time pass on this phone's clock. */
    fun later(ms: Long = 1_000) {
        wall += ms
    }
}
