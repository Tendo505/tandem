package io.github.tendo505.tandem.core

import kotlinx.serialization.Serializable

/**
 * Every change is a single field assignment: "set [field] of [entity] to
 * [value] at time [hlc]". Ops are immutable and idempotent, can arrive in
 * any order and any number of times, and still produce the same result.
 */
@Serializable
data class Op(
    val listId: String,
    val entity: String,
    val field: Field,
    val value: String,
    /** Encoded [Hlc]; also the op's unique id. */
    val hlc: String,
) {
    val id: String get() = hlc
    val timestamp: Hlc get() = Hlc.parse(hlc)
}

/** The fields that can be set. Lists are entities too: their entity id equals the list id. */
@Serializable
enum class Field {
    TITLE,      // list title
    TEXT,       // item text
    DONE,       // "true" / "false"
    POSITION,   // fractional index key
    DELETED,    // "true" hides the entity; "false" restores it
}

/** An item as shown to the user, built by folding ops together. */
data class Item(
    val id: String,
    val text: String,
    val done: Boolean,
    val position: String,
)

data class ListView(val id: String, val title: String, val items: List<Item>)

object Limits {
    const val MAX_TEXT = 200
    const val MAX_OPS_PER_PUSH = 500
    val ID = Regex("^[A-Za-z0-9_-]{8,64}$")
}

/** Checks an op is well-formed. Shared by the app (before sending) and the server (before storing). */
fun Op.validate() {
    require(Limits.ID.matches(listId)) { "invalid list id" }
    require(Limits.ID.matches(entity)) { "invalid entity id" }
    val ts = runCatching { timestamp }.getOrElse { throw IllegalArgumentException("invalid hlc") }
    require(ts.millis > 0) { "invalid hlc" }
    when (field) {
        Field.TITLE, Field.TEXT -> require(value.length <= Limits.MAX_TEXT) { "text longer than ${Limits.MAX_TEXT} characters" }
        Field.DONE, Field.DELETED -> require(value == "true" || value == "false") { "$field must be true or false" }
        Field.POSITION -> require(FractionalIndex.isValid(value)) { "invalid position key" }
    }
    if (field == Field.TITLE) require(entity == listId) { "TITLE belongs to the list entity" }
}
