package com.inkwell.notes.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NaturalOrderTest {
    private val order = NaturalOrder(Locale.ENGLISH)

    @Test fun numbersCompareByValue() {
        val sorted = listOf("Page 10", "Page 2", "Page 1", "Page 02", "Page 100").sortedWith(order)
        assertEquals(listOf("Page 1", "Page 2", "Page 02", "Page 10", "Page 100"), sorted)
    }

    @Test fun dateNamedTitlesSortChronologically() {
        val sorted = listOf("20261015_Physics", "20250101_Physics", "20261001_Art").sortedWith(order)
        assertEquals(listOf("20250101_Physics", "20261001_Art", "20261015_Physics"), sorted)
    }

    @Test fun caseIsIgnoredButOrderIsStable() {
        assertTrue(order.compare("apple", "Banana") < 0)
        assertTrue(order.compare("Apple", "banana") < 0)
        assertTrue(order.compare("abc", "ABC") != 0)
        assertEquals(0, order.compare("same", "same"))
    }

    @Test fun accentsSortNextToTheirBaseLetter() {
        val sorted = listOf("Zebra", "École", "Eagle").sortedWith(order)
        assertEquals(listOf("Eagle", "École", "Zebra"), sorted)
    }

    @Test fun hugeNumbersDoNotOverflow() {
        assertTrue(order.compare("v99999999999999999999", "v100000000000000000000") < 0)
    }

    @Test fun numbersSortBeforeLettersAndOtherDigits() {
        // ICU reads "５" and "⑤" as the digit 5: through the collator "9 Plan" > "５月" > "20261021_…"
        // while "20261021_…" > "9 Plan", a cycle that makes TimSort throw.
        val a = "20261021_Untitled"
        val b = "9 Plan"
        val c = "５月の予定"
        assertTrue(order.compare(b, a) < 0)
        assertTrue(order.compare(b, c) < 0)
        assertTrue(order.compare(a, c) < 0)
        assertTrue(order.compare("2 Plan", "⑤ Plan") < 0)
        assertTrue(order.compare("Plan 9", "Plan A") < 0)
    }

    @Test fun punctuationAndSymbolsStillSortBeforeNumbers() {
        // A leading "_" or "(" keeps a title above the date-named ones, as the collator orders it.
        for (title in listOf("_Inbox", "(Draft) essay", "#todo", "!Important")) {
            assertTrue("$title before a date name", order.compare(title, "20261021_Topic") < 0)
            assertTrue("$title before a number", order.compare(title, "9 Plan") < 0)
            assertTrue("$title before letters", order.compare(title, "Apple") < 0)
            assertTrue(order.compare("20261021_Topic", title) > 0)
        }
        assertEquals(
            listOf("_Inbox", "(Draft)", "9 Plan", "20261021_Topic", "Apple"),
            listOf("Apple", "20261021_Topic", "(Draft)", "9 Plan", "_Inbox").sortedWith(order),
        )
    }

    @Test fun foldersSortLikeDocuments() {
        val folders = com.inkwell.notes.library.NaturalOrder(Locale.ENGLISH)
        val titles = listOf("５月の予定", "Apple", "20261021_Topic", "9 Plan", "_Inbox", "(Draft)", "Plan 10", "plan 2")
        assertEquals(titles.sortedWith(order), titles.sortedWith(folders))
    }

    @Test fun orderIsConsistentOnMixedTitles() {
        val titles = listOf(
            "20261021_Untitled", "9 Plan", "５月の予定", "⑤ notes", "(Draft)", "_x", "Plan",
            "plan 2", "Plan 10", "Plan 02", "École", "０", "0", "00", "a1", "A01", "", " ", "1a", "1 a",
            "_Inbox", "#todo", "!Important", "-dash", "٣ arabic", "x٣", "€5", "~t",
        )
        for (x in titles) for (y in titles) {
            assertEquals("antisymmetry of '$x' and '$y'", Integer.signum(order.compare(x, y)), -Integer.signum(order.compare(y, x)))
            for (z in titles) {
                if (order.compare(x, y) <= 0 && order.compare(y, z) <= 0) {
                    assertTrue("'$x' <= '$y' <= '$z' but not '$x' <= '$z'", order.compare(x, z) <= 0)
                }
            }
        }
        // Long enough for TimSort's merge checks.
        val many = (0 until 400).map { titles[(it * 7919) % titles.size] + (if (it % 3 == 0) "" else " $it") }
        many.sortedWith(order)
    }
}
