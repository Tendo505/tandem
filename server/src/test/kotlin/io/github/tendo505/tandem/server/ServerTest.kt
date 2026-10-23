package io.github.tendo505.tandem.server

import io.github.tendo505.tandem.core.Field
import io.github.tendo505.tandem.core.Hlc
import io.github.tendo505.tandem.core.Op
import io.github.tendo505.tandem.core.ProtocolJson
import io.github.tendo505.tandem.core.PullResponse
import io.github.tendo505.tandem.core.PushRequest
import io.github.tendo505.tandem.core.PushResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    private val now = Instant.parse("2026-11-01T08:00:00Z")
    private val list = "list-aaaa0001"

    private fun op(n: Int, node: String = "phone-a", listId: String = list, millis: Long = now.toEpochMilli() - 60_000 + n) =
        Op(listId, "item-%04d".format(n % 50 + 1), Field.TEXT, "item $n", Hlc(millis, 0, node).encode())

    private fun withServer(beforeCommit: () -> Unit = {}, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        // CI also runs these against a real PostgreSQL (TEST_DATABASE_URL), where
        // concurrent transactions genuinely interleave; H2 mostly serializes them.
        val url = System.getenv("TEST_DATABASE_URL") ?: "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val ds = OpStore.dataSource(url)
        val store = OpStore(ds, beforeCommit).apply { migrate() }
        ds.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM ops"); it.execute("DELETE FROM lists") } }
        application { tandem(store, Clock.fixed(now, ZoneOffset.UTC)) }
        val client = createClient { install(ContentNegotiation) { json(ProtocolJson) } }
        block(client)
    }

    private suspend fun HttpClient.push(ops: List<Op>, listId: String = list) =
        post("/api/lists/$listId/ops") { contentType(ContentType.Application.Json); setBody(PushRequest(ops)) }

    private suspend fun HttpClient.pull(after: Long, limit: Int = 500, listId: String = list): PullResponse =
        get("/api/lists/$listId/ops?after=$after&limit=$limit").body()

    @Test
    fun `push then pull returns ops in order`() = withServer { client ->
        val ops = (1..5).map { op(it) }
        val res: PushResponse = client.push(ops).body()
        assertEquals(5, res.stored)
        val pulled = client.pull(0)
        assertEquals(ops, pulled.ops.map { it.op })
        assertEquals(pulled.ops.map { it.seq }.sorted(), pulled.ops.map { it.seq })
        assertEquals(pulled.ops.last().seq, pulled.latestSeq)
        assertFalse(pulled.hasMore)
    }

    @Test
    fun `re-sending the same ops is harmless`() = withServer { client ->
        val ops = (1..3).map { op(it) }
        client.push(ops)
        val again: PushResponse = client.push(ops + op(4)).body()
        assertEquals(1, again.stored, "only the new op is stored")
        assertEquals(4, client.pull(0).ops.size)
    }

    @Test
    fun `pagination with hasMore`() = withServer { client ->
        client.push((1..25).map { op(it) })
        var cursor = 0L
        val seen = mutableListOf<Op>()
        do {
            val page = client.pull(cursor, limit = 10)
            seen += page.ops.map { it.op }
            cursor = page.ops.lastOrNull()?.seq ?: cursor
        } while (page.hasMore)
        assertEquals(25, seen.size)
    }

    @Test
    fun `lists are isolated`() = withServer { client ->
        client.push(listOf(op(1)))
        client.push(listOf(op(2, listId = "list-bbbb0002")), listId = "list-bbbb0002")
        assertEquals(1, client.pull(0).ops.size)
        assertEquals(1, client.pull(0, listId = "list-bbbb0002").ops.size)
    }

    @Test
    fun `invalid requests are rejected with a reason`() = withServer { client ->
        assertEquals(HttpStatusCode.BadRequest, client.push(listOf(op(1, listId = "list-other01"))).status)
        val badDone = Op(list, "item-0001", Field.DONE, "maybe", Hlc(1, 0, "a").encode())
        assertEquals(HttpStatusCode.BadRequest, client.push(listOf(badDone)).status)
        val future = op(1, millis = now.toEpochMilli() + 3_600_000)
        val res = client.push(listOf(future))
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.body<String>().contains("future"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/lists/bad!/ops").status)
        assertEquals(0, client.pull(0).ops.size, "nothing from rejected batches was stored")
    }

    /**
     * The gap problem: many devices upload to one list concurrently while
     * another device keeps polling with "after = last seq I received". If seq
     * order could differ from commit order, the poller would skip ops forever.
     */
    @Test
    fun `a poller never misses ops while others upload concurrently`() = withServer(beforeCommit = { Thread.sleep((0L..15L).random()) }) { client ->
        val writers = 8
        val perWriter = 40
        coroutineScope {
            val uploads = (0 until writers).map { w ->
                async {
                    (0 until perWriter).chunked(5).forEach { chunk ->
                        client.push(chunk.map { i -> op(w * 1000 + i, node = "phone-$w") })
                    }
                }
            }
            val received = mutableListOf<Op>()
            var cursor = 0L
            val poller = async {
                while (uploads.any { it.isActive } || received.size < writers * perWriter) {
                    val page = client.pull(cursor, limit = 7)
                    received += page.ops.map { it.op }
                    cursor = page.ops.lastOrNull()?.seq ?: cursor
                }
            }
            uploads.awaitAll()
            poller.await()
            assertEquals(writers * perWriter, received.size)
            assertEquals(received.size, received.map { it.hlc }.toSet().size, "no duplicates")
        }
    }
}
