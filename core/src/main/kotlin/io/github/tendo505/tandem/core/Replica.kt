package io.github.tendo505.tandem.core

/**
 * One device's copy of a list's state: for every (entity, field) the op with
 * the latest timestamp. This is a last-writer-wins map, a state-based CRDT:
 * merging is commutative, associative and idempotent, so any two replicas
 * that have seen the same set of ops hold identical state, regardless of
 * the order or how many times ops arrived.
 */
class Replica {
    private val winners = HashMap<Pair<String, Field>, Op>()

    /** Apply an op. Returns true if it changed the visible state. */
    fun apply(op: Op): Boolean {
        val key = op.entity to op.field
        val current = winners[key]
        if (current != null && current.timestamp >= op.timestamp) return false
        winners[key] = op
        return true
    }

    fun applyAll(ops: Iterable<Op>) = ops.forEach(::apply)

    /** The winning op per field, i.e. the minimal state another replica needs to converge. */
    fun state(): Collection<Op> = winners.values

    fun view(listId: String): ListView = materialize(listId, winners.values)

    companion object {
        /**
         * Fold winning field values into the list as the user sees it.
         * Items appear once they have text; deleted items are hidden; order
         * is by position key, then id, so concurrent inserts at the same
         * spot still sort identically on every device.
         */
        fun materialize(listId: String, winningOps: Iterable<Op>): ListView {
            val byEntity = winningOps.filter { it.listId == listId }.groupBy { it.entity }
            fun Map<String, List<Op>>.value(entity: String, field: Field) = this[entity]?.firstOrNull { it.field == field }?.value

            val title = byEntity.value(listId, Field.TITLE) ?: ""
            val items = byEntity.keys
                .filter { it != listId }
                .filter { byEntity.value(it, Field.DELETED) != "true" }
                .mapNotNull { id ->
                    val text = byEntity.value(id, Field.TEXT) ?: return@mapNotNull null
                    Item(id, text, byEntity.value(id, Field.DONE) == "true", byEntity.value(id, Field.POSITION) ?: "")
                }
                .sortedWith(compareBy<Item> { it.position }.thenBy { it.id })
            return ListView(listId, title, items)
        }
    }
}
