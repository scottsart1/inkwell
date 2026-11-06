package com.inkwell.notes.core.storage

import java.text.Collator
import java.util.Locale

/**
 * Natural, case-insensitive ordering for titles: digit runs compare by value
 * ("Lecture 2" < "Lecture 10"), text compares with the locale's collation ignoring case, and
 * titles starting with a number sort after those starting with punctuation or symbols ("_Inbox",
 * "(Draft)") and before those starting with letters or other digits ("５月"). Not thread-safe
 * (Collator); create one per sort.
 */
class NaturalOrder(locale: Locale = Locale.getDefault()) : Comparator<String> {
    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val aDigit = a[i].isAsciiDigit()
            val bDigit = b[j].isAsciiDigit()
            if (aDigit && bDigit) {
                val si = i
                while (i < a.length && a[i].isAsciiDigit()) i++
                val sj = j
                while (j < b.length && b[j].isAsciiDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length.compareTo(nb.length)
                val c = na.compareTo(nb)
                if (c != 0) return c
                val zeros = (i - si).compareTo(j - sj)
                if (zeros != 0) return zeros
            } else if (aDigit != bDigit) {
                // Only at the start (runs are maximal). The text goes before or after every number
                // as a whole, by the collator against one fixed "0": punctuation, symbols and spaces
                // before, letters after. Comparing it with the actual number instead lets the collator
                // read other digits ("５", "⑤") by value, which makes the order intransitive and the
                // sort throw.
                val textAfterNumbers = if (aDigit) sortsAfterNumbers(b, j) else sortsAfterNumbers(a, i)
                return if (aDigit == textAfterNumbers) -1 else 1
            } else {
                val si = i
                while (i < a.length && a[i].isAsciiDigit() == aDigit) i++
                val sj = j
                while (j < b.length && b[j].isAsciiDigit() == bDigit) j++
                val c = collator.compare(a.substring(si, i), b.substring(sj, j))
                if (c != 0) return c
            }
        }
        val rest = (a.length - i).compareTo(b.length - j)
        if (rest != 0) return rest
        return a.compareTo(b)
    }

    /** Whether the text run of [s] at [from] sorts after numbers (collates at or above "0"). */
    private fun sortsAfterNumbers(s: String, from: Int): Boolean {
        var end = from
        while (end < s.length && !s[end].isAsciiDigit()) end++
        return collator.compare(s.substring(from, end), "0") >= 0
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
