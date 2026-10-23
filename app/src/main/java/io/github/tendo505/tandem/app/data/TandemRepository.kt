package io.github.tendo505.tandem.app.data

import io.github.tendo505.tandem.core.Field
import io.github.tendo505.tandem.core.FractionalIndex
import io.github.tendo505.tandem.core.HlcClock
import io.github.tendo505.tandem.core.Item
import io.github.tendo505.tandem.core.Limits
import io.github.tendo505.tandem.core.ListView
import io.github.tendo505.tandem.core.Op
import io.github.tendo505.tandem.core.Replica
import io.github.tendo505.tandem.core.validate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

data class ListSummary(val id: String, val title: String, val total: Int, val done: Int, val pending: Int)

/**
 * Every user action becomes one or more field ops, written to the local
 * database first. The UI never waits for the network; [onLocalChange] just
 * asks for a background sync.
 */
class TandemRepository(
    private val dao: TandemDao,
    private val clock: HlcClock,
    private val onLocalChange: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { randomId() },
) {
    val lists: Flow<List<ListSummary>> =
        combine(dao.observeLists(), dao.observeAllFields(), dao.observePending()) { lists, fields, pending ->
            val byList = fields.groupBy { it.listId }
            val waiting = pending.associate { it.listId to it.n }
            lists.map { l ->
                val view = Replica.materialize(l.id, byList[l.id].orEmpty().map { it.toOp() })
                ListSummary(l.id, view.title, view.items.size, view.items.count { it.done }, waiting[l.id] ?: 0)
            }
        }

    fun list(listId: String): Flow<ListView> =
        dao.observeFields(listId).map { rows -> Replica.materialize(listId, rows.map { it.toOp() }) }

    fun pending(listId: String): Flow<Int> =
        dao.observePending().map { all -> all.firstOrNull { it.listId == listId }?.n ?: 0 }

    suspend fun current(listId: String): ListView =
        Replica.materialize(listId, dao.fields(listId).map { it.toOp() })

    suspend fun createList(title: String): String {
        val id = "list-" + newId()
        dao.insertList(ListEntity(id, joinedAt = now()))
        edit(id, id, Field.TITLE, clean(title))
        return id
    }

    /** Follow a list someone shared. Its contents arrive with the next sync. */
    suspend fun joinList(listId: String) {
        require(Limits.ID.matches(listId)) { "invalid list id" }
        dao.insertList(ListEntity(listId, joinedAt = now()))
        onLocalChange()
    }

    suspend fun rename(listId: String, title: String) = edit(listId, listId, Field.TITLE, clean(title))

    suspend fun addItem(listId: String, text: String): String {
        val last = current(listId).items.map { it.position }.filter(FractionalIndex::isValid).maxOrNull()
        val id = "item-" + newId()
        // Position first: an item becomes visible once it has text, and it should never appear unordered.
        edit(listId, id, Field.POSITION, FractionalIndex.between(last, null))
        edit(listId, id, Field.TEXT, clean(text))
        return id
    }

    suspend fun setText(listId: String, itemId: String, text: String) = edit(listId, itemId, Field.TEXT, clean(text))

    suspend fun setDone(listId: String, itemId: String, done: Boolean) = edit(listId, itemId, Field.DONE, done.toString())

    suspend fun delete(listId: String, itemId: String) = edit(listId, itemId, Field.DELETED, "true")

    suspend fun restore(listId: String, itemId: String) = edit(listId, itemId, Field.DELETED, "false")

    suspend fun clearDone(listId: String) {
        current(listId).items.filter { it.done }.forEach { delete(listId, it.id) }
    }

    /** Move an item [by] places (negative is up). Only that item's position changes, so it merges with any concurrent edit. */
    suspend fun move(listId: String, itemId: String, by: Int) {
        val items = current(listId).items
        val from = items.indexOfFirst { it.id == itemId }
        if (from < 0) return
        val to = (from + by).coerceIn(0, items.lastIndex)
        if (to == from) return
        val others = items.filterIndexed { i, _ -> i != from }
        edit(listId, itemId, Field.POSITION, keyAt(others, to))
    }

    /** Remove the list from this phone only. */
    suspend fun leave(listId: String) = dao.forget(listId)

    private suspend fun edit(listId: String, entity: String, field: Field, value: String) {
        val op = Op(listId, entity, field, value, clock.tick().encode())
        op.validate()
        dao.recordLocal(op)
        onLocalChange()
    }

    companion object {
        private val CODE = Regex("list-[A-Za-z0-9_-]{3,59}")

        /** Find a list code in whatever the user pasted, e.g. the whole share message. */
        fun parseCode(text: String): String? = CODE.find(text)?.value?.takeIf(Limits.ID::matches)

        private fun clean(text: String) = text.trim().replace(Regex("\\s+"), " ").take(Limits.MAX_TEXT)

        /**
         * A key that sorts at index [index] of [items]. Two devices appending
         * at the same moment can produce equal keys (ties sort by id); a key
         * strictly between equal keys doesn't exist, so in that rare case the
         * item goes right after the tied group instead.
         */
        internal fun keyAt(items: List<Item>, index: Int): String {
            val before = items.getOrNull(index - 1)?.position?.takeIf(FractionalIndex::isValid)
            val after = items.drop(index).map { it.position }
                .firstOrNull { FractionalIndex.isValid(it) && (before == null || it > before) }
            return FractionalIndex.between(before, after)
        }
    }
}

private val random = SecureRandom()

fun randomId(length: Int = 12): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return String(CharArray(length) { chars[random.nextInt(chars.length)] })
}
