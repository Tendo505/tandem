package io.github.tendo505.tandem.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlcTest {
    @Test
    fun `encoding sorts like the timestamps`() {
        val stamps = listOf(Hlc(5, 0, "b"), Hlc(5, 1, "a"), Hlc(10, 0, "a"), Hlc(5, 0, "a"), Hlc(1_700_000_000_000, 99_999, "z"))
        assertEquals(stamps.sorted(), stamps.sortedBy { it.encode() })
        stamps.forEach { assertEquals(it, Hlc.parse(it.encode())) }
    }

    @Test
    fun `clock never goes backwards even when the wall clock does`() {
        var wall = 1_000L
        val clock = HlcClock("phone-a", { wall })
        val a = clock.tick()
        wall = 500 // user set their clock back
        val b = clock.tick()
        val c = clock.tick()
        assertTrue(a < b && b < c)
        assertEquals(1_000, c.millis)
    }

    @Test
    fun `after seeing a remote timestamp, local edits come after it`() {
        val clock = HlcClock("phone-a", { 1_000 })
        val remote = Hlc(5_000, 7, "phone-b") // the other phone's clock runs ahead
        clock.observe(remote)
        assertTrue(clock.tick() > remote)
    }

    @Test
    fun `counter overflow rolls into the next millisecond`() {
        val clock = HlcClock("n", { 1 }, Hlc(1, Hlc.MAX_COUNTER, "n"))
        assertEquals(Hlc(2, 0, "n"), clock.tick())
    }

    @Test
    fun `invalid timestamps are rejected`() {
        assertFailsWith<IllegalArgumentException> { Hlc.parse("nope") }
        assertFailsWith<IllegalArgumentException> { Hlc(1, 0, "bad node!") }
    }
}

class FractionalIndexTest {
    @Test
    fun `between produces strictly ordered valid keys`() {
        assertEquals("V", FractionalIndex.between(null, null))
        assertEquals("W", FractionalIndex.between("V", null))
        listOf(null to "1", "1" to "2", "1" to "11", "1V" to "2", "z" to null, "y" to "z", "0V" to "1").forEach { (a, b) ->
            val k = FractionalIndex.between(a, b)
            assertTrue(FractionalIndex.isValid(k), "$k valid")
            if (a != null) assertTrue(a < k, "$a < $k")
            if (b != null) assertTrue(k < b, "$k < $b")
        }
    }

    @Test
    fun `property - thousands of random inserts keep every key distinct, valid and ordered`() {
        val rnd = Random(1)
        val keys = mutableListOf<String>()
        repeat(3_000) {
            val i = rnd.nextInt(keys.size + 1) // insert at a random gap
            val k = FractionalIndex.between(keys.getOrNull(i - 1), keys.getOrNull(i))
            keys.add(i, k)
        }
        assertEquals(keys.sorted(), keys)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all(FractionalIndex::isValid))
    }

    @Test
    fun `appending stays short`() {
        val keys = FractionalIndex.sequence(500)
        assertEquals(keys.sorted(), keys)
        assertTrue(keys.last().length <= 10, "append-only keys grow slowly: ${keys.last()}")
    }

    @Test
    fun `rejects bad input`() {
        assertFailsWith<IllegalArgumentException> { FractionalIndex.between("b", "a") }
        assertFailsWith<IllegalArgumentException> { FractionalIndex.between("a0", null) } // may not end in the smallest digit
        assertFalse(FractionalIndex.isValid(""))
    }
}

class ReplicaTest {
    private val list = "list-0001"
    private fun op(entity: String, field: Field, value: String, millis: Long, node: String = "a") =
        Op(list, entity, field, value, Hlc(millis, 0, node).encode())

    @Test
    fun `latest write wins per field, independently`() {
        val r = Replica()
        r.apply(op("item-0001", Field.TEXT, "milk", 1))
        r.apply(op("item-0001", Field.POSITION, "V", 1))
        r.apply(op("item-0001", Field.DONE, "true", 3, "b"))
        r.apply(op("item-0001", Field.TEXT, "oat milk", 2, "b"))
        assertFalse(r.apply(op("item-0001", Field.TEXT, "old", 1, "c")), "older write loses")
        assertEquals(listOf(Item("item-0001", "oat milk", true, "V")), r.view(list).items)
    }

    @Test
    fun `equal millis break ties by counter then node, the same everywhere`() {
        val a = op("item-0001", Field.TEXT, "from a", 5, "a")
        val b = op("item-0001", Field.TEXT, "from b", 5, "b")
        val r1 = Replica().apply { apply(a); apply(b) }
        val r2 = Replica().apply { apply(b); apply(a) }
        assertEquals("from b", r1.view(list).items.single().text)
        assertEquals(r1.view(list), r2.view(list))
    }

    @Test
    fun `deleted items are hidden and can be restored`() {
        val r = Replica()
        r.apply(op("item-0001", Field.TEXT, "eggs", 1))
        r.apply(op("item-0001", Field.DELETED, "true", 2))
        assertTrue(r.view(list).items.isEmpty())
        r.apply(op("item-0001", Field.DELETED, "false", 3))
        assertEquals("eggs", r.view(list).items.single().text)
    }

    @Test
    fun `validation catches malformed ops`() {
        op("item-0001", Field.TEXT, "ok", 1).validate()
        assertFailsWith<IllegalArgumentException> { op("item-0001", Field.DONE, "maybe", 1).validate() }
        assertFailsWith<IllegalArgumentException> { op("item-0001", Field.TEXT, "x".repeat(201), 1).validate() }
        assertFailsWith<IllegalArgumentException> { op("item-0001", Field.TITLE, "t", 1).validate() }
        assertFailsWith<IllegalArgumentException> { op("item-0001", Field.POSITION, "a0", 1).validate() }
    }

    /**
     * The key guarantee. Three devices edit the same list concurrently while
     * offline (random adds, edits, ticks, moves and deletes, all on their own
     * clocks). Ops are then delivered to each device in a different random
     * order, with duplicates. Every device must end up with exactly the same
     * list, and it must equal the list built from the ops in timestamp order.
     */
    @Test
    fun `property - replicas converge regardless of delivery order and duplicates`() {
        repeat(200) { run ->
            val rnd = Random(run)
            val nodes = listOf("phone-a", "phone-b", "phone-c")
            val clocks = nodes.associateWith { n -> var t = 1_000L + rnd.nextLong(50); HlcClock(n, { t += rnd.nextLong(3); t }) }
            val local = nodes.associateWith { Replica() }
            val all = mutableListOf<Op>()
            val itemIds = (1..6).map { "item-%04d".format(it) }

            repeat(rnd.nextInt(20, 80)) {
                val node = nodes.random(rnd)
                val replica = local.getValue(node)
                val existing = replica.view(list).items
                val id = itemIds.random(rnd)
                val field = Field.entries.filter { it != Field.TITLE }.random(rnd)
                val value = when (field) {
                    Field.TEXT -> "text-${rnd.nextInt(100)}"
                    Field.DONE, Field.DELETED -> rnd.nextBoolean().toString()
                    Field.POSITION -> {
                        val i = rnd.nextInt(existing.size + 1)
                        val before = existing.getOrNull(i - 1)?.position?.takeIf { it.isNotEmpty() }
                        val after = existing.getOrNull(i)?.position?.takeIf { it.isNotEmpty() && (before == null || it > before) }
                        FractionalIndex.between(before, after)
                    }
                    Field.TITLE -> error("unreachable")
                }
                val o = Op(list, id, field, value, clocks.getValue(node).tick().encode())
                o.validate()
                replica.apply(o)
                all += o
                // Occasionally a device syncs part of what others did, like a flaky network.
                if (rnd.nextInt(5) == 0) {
                    val other = local.getValue(nodes.random(rnd))
                    all.shuffled(rnd).take(all.size / 2).forEach { remote ->
                        other.apply(remote); clocks.getValue(node).observe(remote.timestamp)
                    }
                }
            }

            val expected = Replica().apply { applyAll(all.sortedBy { it.timestamp }) }.view(list)
            for (node in nodes) {
                val r = local.getValue(node)
                val delivery = (all + all.shuffled(rnd).take(all.size / 3)).shuffled(rnd)
                r.applyAll(delivery)
                assertEquals(expected, r.view(list), "run $run: $node diverged")
            }
        }
    }
}
