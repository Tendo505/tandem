package io.github.tendo505.tandem.core

/**
 * Fractional indexing: order keys that are strings, where a new key can
 * always be made between any two existing ones ("a" < "an" < "b").
 *
 * Moving or inserting an item only writes that one item's key, so two
 * devices reordering at the same time never conflict over renumbering the
 * rest of the list. Keys are base-62 digits read as a fraction (0.xyz), and
 * never end in the smallest digit, which guarantees there's always room
 * below a key as well as above it.
 */
object FractionalIndex {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = DIGITS.length
    private val VALID = Regex("^[0-9A-Za-z]*[1-9A-Za-z]$")

    fun isValid(key: String) = key.length <= 64 && VALID.matches(key)

    private fun digit(c: Char) = DIGITS.indexOf(c).also { require(it >= 0) { "invalid digit '$c'" } }

    /**
     * A key strictly between [a] and [b]. Null [a] means "before everything",
     * null [b] means "after everything".
     */
    fun between(a: String?, b: String?): String {
        if (a != null) require(isValid(a)) { "invalid key '$a'" }
        if (b != null) require(isValid(b)) { "invalid key '$b'" }
        if (a != null && b != null) require(a < b) { "'$a' must sort before '$b'" }
        // Appending and prepending are the common cases. Stepping by one digit
        // keeps keys short (a new character every ~61 items); halving the gap
        // each time would add a character every few items.
        if (a != null && b == null) return increment(a)
        if (a == null && b != null) return decrement(b)
        return midpoint(a ?: "", b)
    }

    /** The shortest simple key greater than [a]. */
    private fun increment(a: String): String {
        if (a.isEmpty()) return DIGITS[1].toString()
        val d = digit(a[0])
        return if (d < BASE - 1) DIGITS[d + 1].toString() else a[0] + increment(a.drop(1))
    }

    /** A short key less than [b] (and greater than "", so never all zeros). */
    private fun decrement(b: String): String {
        val d = digit(b[0])
        return when {
            d > 1 -> DIGITS[d - 1].toString()
            d == 1 && b.length > 1 -> "1"                       // "1" < "1x"
            d == 1 -> DIGITS[0] + DIGITS[BASE - 1].toString()   // "0z" < "1"
            else -> DIGITS[0] + decrement(b.drop(1))            // stay under "0..."
        }
    }

    /** Midpoint of fractions 0.a and 0.b (b = null means 1.0). Requires a < b. */
    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            // Skip the shared prefix: the answer starts with it.
            var n = 0
            while (n < b.length && (a.getOrNull(n) ?: '0') == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.drop(n))
        }
        val da = if (a.isEmpty()) 0 else digit(a[0])
        val db = if (b == null) BASE else digit(b[0])
        if (db - da > 1) return DIGITS[(da + db) / 2].toString()
        // Adjacent first digits: if b continues, its first digit alone is already in between.
        if (b != null && b.length > 1) return b.substring(0, 1)
        // Otherwise keep a's first digit and find room after the rest of a.
        return DIGITS[da] + midpoint(a.drop(1), null)
    }

    /** [count] evenly spread keys, for seeding a new list. */
    fun sequence(count: Int): List<String> {
        val keys = ArrayList<String>(count)
        var last: String? = null
        repeat(count) { last = between(last, null).also(keys::add) }
        return keys
    }
}
