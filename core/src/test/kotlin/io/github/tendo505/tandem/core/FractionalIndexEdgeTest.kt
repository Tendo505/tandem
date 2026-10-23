package io.github.tendo505.tandem.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FractionalIndexEdgeTest {
    @Test
    fun `prepending many items stays ordered, valid and short`() {
        val keys = ArrayDeque<String>()
        repeat(500) { keys.addFirst(FractionalIndex.between(null, keys.firstOrNull())) }
        assertEquals(keys.sorted(), keys.toList())
        assertTrue(keys.all(FractionalIndex::isValid))
        assertTrue(keys.first().length <= 10, "prepend-only keys grow slowly: ${keys.first()}")
    }

    @Test
    fun `mixed appends, prepends and inserts never break ordering`() {
        val rnd = kotlin.random.Random(9)
        val keys = mutableListOf<String>()
        repeat(2_000) {
            when (rnd.nextInt(3)) {
                0 -> keys.add(FractionalIndex.between(keys.lastOrNull(), null))
                1 -> keys.add(0, FractionalIndex.between(null, keys.firstOrNull()))
                else -> { val i = rnd.nextInt(keys.size + 1); keys.add(i, FractionalIndex.between(keys.getOrNull(i - 1), keys.getOrNull(i))) }
            }
        }
        assertEquals(keys.sorted(), keys)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all(FractionalIndex::isValid))
    }
}
