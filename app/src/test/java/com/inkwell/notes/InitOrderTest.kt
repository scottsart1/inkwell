package com.inkwell.notes

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kotlin runs property initialisers and `init` blocks in declaration order. An `init` block that
 * launches on Main.immediate (viewModelScope) runs its coroutine at once, so a property declared
 * after the block is still null there: the assistant panel crashed this way on every open
 * (`configured` was declared below `init`). This guards every class in the app against it.
 */
class InitOrderTest {
    private val property = Regex("""^    (?:(?:private|internal|protected|override|public)\s+)*(?:lateinit\s+)?(?:val|var)\s+\w+(?:\s*:\s*[^=]*?)?\s*(?:=|by)\s""")

    @Test
    fun noStoredPropertyIsDeclaredAfterALaunchingInitBlock() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("sources not found from ${File(".").absolutePath}", sources.isNotEmpty())
        val offenders = sources.flatMap { offenders(it) }
        assertTrue("Declare these above the init block that launches:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private fun offenders(file: File): List<String> {
        val lines = file.readLines()
        val found = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].startsWith("    init {")) { i++; continue }
            var depth = 0
            var end = i
            for (j in i until lines.size) {
                depth += lines[j].count { it == '{' } - lines[j].count { it == '}' }
                end = j
                if (depth == 0) break
            }
            val body = lines.subList(i, end + 1).joinToString("\n")
            if ("launch" in body) {
                for (k in end + 1 until lines.size) {
                    val line = lines[k]
                    if (line.startsWith("}")) break
                    if (property.containsMatchIn(line) && "get()" !in line.substringBefore("=")) {
                        found += "${file.path}:${k + 1}: ${line.trim()}"
                    }
                }
            }
            i = end + 1
        }
        return found
    }
}
