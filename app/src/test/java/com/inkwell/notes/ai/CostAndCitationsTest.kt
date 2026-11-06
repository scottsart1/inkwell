package com.inkwell.notes.ai

import com.inkwell.notes.ai.context.Citations
import com.inkwell.notes.ai.context.SourceLabels
import com.inkwell.notes.ai.engine.CostCalculator
import com.inkwell.notes.ai.engine.CostFormat
import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.engine.MonthWindow
import com.inkwell.notes.ai.engine.PriceOverride
import com.inkwell.notes.ai.engine.TokenEstimator
import com.inkwell.notes.ai.net.AiFailure
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPart
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CostAndCitationsTest {
    private val h = EngineHarness()
    @After fun tearDown() = h.close()

    private fun req(text: String, model: ModelRef? = null) =
        AiRequest(AiTask.CHAT, listOf(AiMessage(AiRole.USER, text)), system = "abcd", model = model)

    @Test fun tokenEstimateIsAboutFourCharsPerToken() {
        assertEquals(0, TokenEstimator.text(""))
        assertEquals(1, TokenEstimator.text("abc"))
        assertEquals(250, TokenEstimator.text("x".repeat(1000)))
        // system (1) + message overhead (4) + 1000 chars (250)
        assertEquals(255, h.engine.estimateTokens(req("y".repeat(1000))))
    }

    @Test fun imageTokensFromPngHeader() {
        val png = ByteArray(32).also {
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(it)
            // IHDR width=750, height=1000 at offsets 16..23
            byteArrayOf(0, 0, 0x02, 0xEE.toByte(), 0, 0, 0x03, 0xE8.toByte()).copyInto(it, 16)
        }
        assertEquals(750 to 1000, TokenEstimator.imageSize(png))
        assertEquals(1000, TokenEstimator.image(AiPart.Image(png)))
        assertEquals(1200, TokenEstimator.image(AiPart.Image(ByteArray(4))))
    }

    @Test fun costFromPriceTable() {
        val opus = ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5")
        val spec = h.models.spec(opus)
        assertEquals(4.0 + 20.0, CostCalculator.tokens(spec, 1_000_000, 1_000_000)!!, 1e-9)
        val haiku = h.models.spec(ModelRef(AiProviderId.ANTHROPIC, "claude-haiku-5-5"))
        assertEquals(0.10 * 0.1 + 0.50, CostCalculator.tokens(haiku, 100_000, 1_000_000)!!, 1e-9)
        // Over 100K input tokens the whole request is billed at the long-context prices.
        assertEquals(0.50 + 2.50, CostCalculator.tokens(haiku, 1_000_000, 1_000_000)!!, 1e-9)
        val fable = h.models.spec(ModelRef(AiProviderId.ANTHROPIC, "claude-fable-5-1"))
        assertEquals(10.0, fable.inputUsdPerMTok!!, 0.0)
        assertEquals(50.0, fable.outputUsdPerMTok!!, 0.0)
        assertEquals(200_000, h.models.spec(ModelRef(AiProviderId.ANTHROPIC, "claude-haiku-4-5")).contextTokens)
        assertEquals(1_000_000, h.models.spec(ModelRef(AiProviderId.ANTHROPIC, "claude-sonnet-4-6")).contextTokens)

        val est = h.engine.estimateCostUsd(req("z".repeat(4000), opus), expectedOutputTokens = 1000)!!
        val inTok = h.engine.estimateTokens(req("z".repeat(4000), opus))
        assertEquals(inTok * 4.0 / 1e6 + 1000 * 20.0 / 1e6, est, 1e-12)
    }

    @Test fun unknownModelHasNoCostUntilOverridden() {
        val ref = ModelRef(AiProviderId.MOONSHOT, "kimi-latest")
        assertNull(h.engine.estimateCostUsd(req("hi", ref), 100))
        h.models.setPriceOverride(ref, PriceOverride(1.0, 3.0))
        val est = h.engine.estimateCostUsd(req("hi", ref), 100)!!
        assertEquals(h.engine.estimateTokens(req("hi", ref)) * 1.0 / 1e6 + 100 * 3.0 / 1e6, est, 1e-12)
        h.models.setPriceOverride(ref, null)
        assertNull(h.engine.estimateCostUsd(req("hi", ref), 100))
    }

    @Test fun audioAndEmbeddingCosts() {
        val whisper = h.models.spec(ModelRef(AiProviderId.OPENAI, "whisper-1"))
        assertEquals(0.006 * 2.5, CostCalculator.audio(whisper, 150_000)!!, 1e-12)
        val emb = h.models.spec(ModelRef(AiProviderId.OPENAI, "text-embedding-3-small"))
        assertEquals(0.02, CostCalculator.tokens(emb, 1_000_000, 0)!!, 1e-12)
    }

    @Test fun hardLimitRefusesRequestsWithoutCallingProvider() {
        h.prefs = AiPrefs(monthlyHardLimitUsd = 5.0)
        h.usage.spentThisMonth = 5.0
        val failed = h.events(req("hello", ModelRef(AiProviderId.OPENAI, "gpt-4o"))).single() as EngineEvent.Failed
        assertEquals(AiFailure.Kind.LIMIT, failed.failure.kind)
        assertTrue(failed.failure.message.contains("limit of $5.00"))
        assertEquals(0, h.server.requestCount)
    }

    @Test fun oversizedRequestFailsBeforeSending() {
        val failed = h.events(req("w".repeat(40_000), ModelRef(AiProviderId.MOONSHOT, "moonshot-v1-8k"))).single() as EngineEvent.Failed
        assertEquals(AiFailure.Kind.CONTEXT_TOO_LONG, failed.failure.kind)
        assertEquals(0, h.server.requestCount)
    }

    @Test fun monthWindowStartsAtLocalMidnightOfFirstDay() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = LocalDateTime.of(2026, 10, 16, 14, 5).atZone(zone).toInstant().toEpochMilli()
        val start = LocalDateTime.of(2026, 10, 1, 0, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(start, MonthWindow.startOfMonth(now, zone))
    }

    @Test fun costFormatting() {
        assertEquals("price unknown", CostFormat.usd(null))
        assertEquals("< $0.0001", CostFormat.usd(0.00001))
        assertEquals("$0.0042", CostFormat.usd(0.0042))
        assertEquals("$0.123", CostFormat.usd(0.1234))
        assertEquals("$12.50", CostFormat.usd(12.5))
        assertEquals("12,345", CostFormat.tokens(12_345))
    }

    // ---- citations

    @Test fun parsesPageCitations() {
        val c = Citations.parse("Rates rose [p.3], then fell [p. 5, 7]. See [pp. 9-11] and [p.3] again.")
        assertEquals(listOf("p.3", "p.5", "p.7", "p.9", "p.10", "p.11"), c.map { it.label })
        assertEquals(listOf(3, 5, 7, 9, 10, 11), c.map { it.page })
    }

    @Test fun parsesRecordingAndAddedDocumentCitations() {
        val c = Citations.parse("As said [rec 18:40] and [D2 p.4; D2 p.6], also [rec 1:02:03].")
        assertEquals(listOf("rec 18:40", "D2 p.4", "D2 p.6", "rec 1:02:03"), c.map { it.label })
        assertEquals((18 * 60 + 40) * 1000L, c[0].recordingMs)
        assertEquals("D2", c[1].docTag)
        assertEquals(3_723_000L, c[3].recordingMs)
    }

    @Test fun ignoresNonCitationBrackets() {
        val c = Citations.parse("The word [illegible] and [?maybe?] and a [link](http://x) and [1] and [p.0] and [rec 1:75]")
        assertTrue(c.isEmpty())
    }

    @Test fun sourceLabelFormatting() {
        assertEquals("p.3", SourceLabels.page(2))
        assertEquals("D3 p.1", SourceLabels.page(0, "D3"))
        assertEquals("rec 18:40", SourceLabels.recording(1_120_000))
        assertEquals("rec 1:00:05", SourceLabels.recording(3_605_000))
    }
}
