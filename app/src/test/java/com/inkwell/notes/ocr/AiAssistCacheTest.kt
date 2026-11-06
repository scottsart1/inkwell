package com.inkwell.notes.ocr

import com.inkwell.notes.ocr.work.CachedImageOcr
import com.inkwell.notes.ocr.work.ImageOcrCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A scanned page the vision model found no text on must not be sent (and billed) again on every annotation. */
class AiAssistCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val lowConfidence = CachedImageOcr(
        engine = "mlkit-text",
        blocks = listOf(CachedImageOcr.CachedBlock("~ ., ;", listOf(0.1f, 0.1f, 0.2f, 0.2f), 0.2f)),
        confidence = 0.2f,
    )

    @Test
    fun lowConfidenceLocalOcrAsksForAiUntilTheModelHasLooked() {
        assertTrue(lowConfidence.needsAiAssist(0.6f))
        assertFalse("confident enough", lowConfidence.needsAiAssist(0.1f))
        assertFalse("the model already found no text", lowConfidence.copy(aiTried = true).needsAiAssist(0.6f))
        assertFalse("already an AI transcription", CachedImageOcr("ai:OPENAI:gpt-4o", lowConfidence.blocks, 0.2f).needsAiAssist(0.6f))
        assertFalse("unknown confidence", lowConfidence.copy(confidence = null).needsAiAssist(0.6f))
    }

    @Test
    fun theCheckedMarkSurvivesTheCacheAndOlderFilesReadAsUnchecked() = runTest {
        val dir = tmp.newFolder("cache")
        val cache = ImageOcrCache(dir)
        cache.put("doc", "pdfpage-3", 1234, lowConfidence.copy(aiTried = true))
        val back = cache.get("doc", "pdfpage-3", 1234)!!
        assertTrue(back.aiTried)
        assertFalse(back.needsAiAssist(0.6f))

        // Files written before the mark existed.
        cache.put("doc", "pdfpage-4", 1234, lowConfidence)
        val old = dir.listFiles()!!.single { it.name.contains("pdfpage-4") }
        assertFalse(old.readText().contains("aiTried"))
        val decoded = cache.get("doc", "pdfpage-4", 1234)!!
        assertEquals(lowConfidence, decoded)
        assertTrue(decoded.needsAiAssist(0.6f))
    }
}
