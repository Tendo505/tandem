package io.github.tendo505.tandem.server

import io.github.tendo505.tandem.core.ErrorResponse
import io.github.tendo505.tandem.core.Limits
import io.github.tendo505.tandem.core.ProtocolJson
import io.github.tendo505.tandem.core.PullResponse
import io.github.tendo505.tandem.core.PushRequest
import io.github.tendo505.tandem.core.PushResponse
import io.github.tendo505.tandem.core.validate
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Duration

/** Wire up the HTTP API around an [OpStore]. Separate from main() so tests can run it in-process. */
fun Application.tandem(store: OpStore, clock: Clock = Clock.systemUTC(), maxClockSkew: Duration = Duration.ofMinutes(5)) {
    install(ContentNegotiation) { json(ProtocolJson) }
    install(CallLogging)
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request")) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request")) }
    }

    routing {
        get("/health") { call.respondText("ok") }

        post("/api/lists/{listId}/ops") {
            val listId = call.parameters["listId"]!!
            require(Limits.ID.matches(listId)) { "invalid list id" }
            val req = call.receive<PushRequest>()
            require(req.ops.size <= Limits.MAX_OPS_PER_PUSH) { "at most ${Limits.MAX_OPS_PER_PUSH} ops per request" }
            val latestAllowed = clock.millis() + maxClockSkew.toMillis()
            for (op in req.ops) {
                op.validate()
                require(op.listId == listId) { "op belongs to a different list" }
                // A device with a clock far in the future would win every
                // conflict for as long as its clock is ahead. Refuse those ops.
                require(op.timestamp.millis <= latestAllowed) { "op timestamp is in the future; check the device clock" }
            }
            val (stored, latest) = store.append(listId, req.ops)
            call.respond(PushResponse(stored, latest))
        }

        get("/api/lists/{listId}/ops") {
            val listId = call.parameters["listId"]!!
            require(Limits.ID.matches(listId)) { "invalid list id" }
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 1000)
            val (ops, latest) = store.after(listId, after, limit + 1)
            call.respond(PullResponse(ops.take(limit), latest, hasMore = ops.size > limit))
        }
    }
}

fun main() {
    val store = OpStore(OpStore.dataSource(System.getenv("DATABASE_URL"))).apply { migrate() }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8090
    embeddedServer(Netty, port = port, host = "0.0.0.0") { tandem(store) }.start(wait = true)
}
