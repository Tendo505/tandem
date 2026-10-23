package io.github.tendo505.tandem.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The sync protocol, shared by server and app so they can't drift apart.
 *
 *   POST /api/lists/{id}/ops           upload local ops (idempotent: re-sending is harmless)
 *   GET  /api/lists/{id}/ops?after=N   ops the server stored after sequence number N
 *
 * The server never interprets ops: it is a durable, ordered log per list.
 * All merging happens on devices, with the same rules everywhere.
 */
@Serializable
data class PushRequest(val ops: List<Op>)

@Serializable
data class PushResponse(val stored: Int, val latestSeq: Long)

@Serializable
data class SeqOp(val seq: Long, val op: Op)

/**
 * Clients must advance their cursor to the seq of the last op they actually
 * received, never to [latestSeq]: that is informational (for "N changes
 * behind" displays) and may already include ops committed after this page
 * was read.
 */
@Serializable
data class PullResponse(val ops: List<SeqOp>, val latestSeq: Long, val hasMore: Boolean)

@Serializable
data class ErrorResponse(val error: String)

val ProtocolJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
