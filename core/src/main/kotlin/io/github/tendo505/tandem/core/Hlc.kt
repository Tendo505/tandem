package io.github.tendo505.tandem.core

/**
 * Hybrid logical clock timestamp: wall-clock milliseconds, a counter for
 * events within the same millisecond, and the id of the device that made it.
 *
 * Ordering is (millis, counter, node), so it is total: two different devices
 * can never produce equal timestamps, and every replica compares them the same
 * way. That is what makes "latest write wins" deterministic everywhere.
 */
data class Hlc(val millis: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    init {
        require(millis >= 0) { "millis must be non-negative" }
        require(counter in 0..MAX_COUNTER) { "counter out of range" }
        require(NODE.matches(node)) { "node must be 1-32 characters of [A-Za-z0-9_-]" }
    }

    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::millis, Hlc::counter, Hlc::node)

    /** Fixed-width text form that sorts the same way as [compareTo], so databases can compare it as a string. */
    fun encode(): String = "%015d-%05d-%s".format(millis, counter, node)

    override fun toString() = encode()

    companion object {
        const val MAX_COUNTER = 99_999
        private val NODE = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val FORMAT = Regex("^(\\d{15})-(\\d{5})-([A-Za-z0-9_-]{1,32})$")

        fun parse(s: String): Hlc {
            val m = FORMAT.matchEntire(s) ?: throw IllegalArgumentException("not an HLC timestamp: $s")
            return Hlc(m.groupValues[1].toLong(), m.groupValues[2].toInt(), m.groupValues[3])
        }
    }
}

/**
 * Generates timestamps for one device. Unlike the plain wall clock it never
 * goes backwards (even if the phone's clock is adjusted), and after seeing a
 * remote timestamp it always issues later ones, so an edit made after
 * reading someone else's change is guaranteed to win over it.
 */
class HlcClock(
    val node: String,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private var last: Hlc = Hlc(0, 0, node),
) {
    val latest: Hlc get() = last

    /** A timestamp for a local edit. */
    @Synchronized
    fun tick(): Hlc {
        val wall = wallClock()
        last = if (wall > last.millis) Hlc(wall, 0, node) else bump(last.millis, last.counter)
        return last
    }

    /** Merge in a timestamp received from another device. */
    @Synchronized
    fun observe(remote: Hlc) {
        val wall = wallClock()
        val millis = maxOf(wall, last.millis, remote.millis)
        last = when {
            millis == last.millis && millis == remote.millis -> bump(millis, maxOf(last.counter, remote.counter))
            millis == last.millis -> bump(millis, last.counter)
            millis == remote.millis -> bump(millis, remote.counter)
            else -> Hlc(millis, 0, node)
        }
    }

    private fun bump(millis: Long, counter: Int): Hlc =
        if (counter < Hlc.MAX_COUNTER) Hlc(millis, counter + 1, node) else Hlc(millis + 1, 0, node)
}
