package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.provider.StopReason
import com.inkwell.notes.core.model.AiEvent
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiProviderSettings
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnthropicStreamTest {
    private val h = EngineHarness()
    private val opus = ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5")

    @After fun tearDown() = h.close()

    private fun request(model: ModelRef = opus, task: AiTask = AiTask.CHAT) = AiRequest(
        task, listOf(AiMessage(AiRole.USER, "Explain photosynthesis.")), system = "You help with notes.",
        model = model, temperature = 0.3f,
    )

    private fun stream(vararg events: String) = events.joinToString("")

    private val start = sseEvent("message_start", """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"usage":{"input_tokens":25,"output_tokens":1}}}""")
    private val stop = sseEvent("message_stop", """{"type":"message_stop"}""")
    private fun delta(index: Int, text: String) = sseEvent("content_block_delta", """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":"$text"}}""")
    private fun messageDelta(reason: String, out: Int) = sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"$reason","stop_sequence":null},"usage":{"output_tokens":$out}}""")

    @Test fun streamsTextIgnoringThinkingAndOtherBlocks() {
        h.enqueueSse(
            stream(
                start,
                sseEvent("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"""),
                sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me think"}}"""),
                sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"abc"}}"""),
                sseEvent("content_block_stop", """{"type":"content_block_stop","index":0}"""),
                sseEvent("ping", """{"type": "ping"}"""),
                sseEvent("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"fallback","model":"x"}}"""),
                sseEvent("content_block_stop", """{"type":"content_block_stop","index":1}"""),
                sseEvent("content_block_start", """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}"""),
                delta(2, "Plants "),
                delta(2, "use light."),
                sseEvent("content_block_stop", """{"type":"content_block_stop","index":2}"""),
                messageDelta("end_turn", 9),
                stop,
            ),
        )
        val events = h.events(request())
        val done = events.last() as EngineEvent.Completed
        assertEquals("Plants use light.", done.text)
        assertEquals("", done.reasoning)
        assertEquals(25, done.usage.inputTokens)
        assertEquals(9, done.usage.outputTokens)
        assertEquals(StopReason.END, done.stop)
        // claude-opus-5-5: $4 in / $20 out per million.
        assertEquals(25 * 4.0 / 1e6 + 9 * 20.0 / 1e6, done.usage.estimatedCostUsd!!, 1e-12)
    }

    @Test fun sendsOfficialHeadersEffortAndFallbacks() {
        h.enqueueSse(stream(start, delta(0, "Hi"), messageDelta("end_turn", 1), stop))
        h.events(request())
        val rec = h.take()
        assertEquals("/v1/messages", rec.path)
        assertEquals("sk-ant-test-0000000000", rec.getHeader("x-api-key"))
        assertEquals("2023-06-01", rec.getHeader("anthropic-version"))
        assertEquals("application/json", rec.getHeader("Content-Type"))
        assertEquals("server-side-fallback-2026-07-01", rec.getHeader("anthropic-beta"))
        assertNull(rec.getHeader("Authorization"))
        val body = rec.json()
        assertEquals("claude-opus-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(16000, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("You help with notes.", body["system"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", body["fallbacks"]!!.jsonPrimitive.content)
        for (k in listOf("temperature", "top_p", "top_k", "thinking")) assertFalse("must not send $k", body.containsKey(k))
        val msg = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", msg["role"]!!.jsonPrimitive.content)
        assertEquals("text", msg["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun customEndpointSkipsFallbacks() {
        h.prefs = AiPrefs(providers = AiProviderId.entries.map { AiProviderSettings(it, customBaseUrl = if (it == AiProviderId.ANTHROPIC) h.base else null) })
        h.enqueueSse(stream(start, delta(0, "Hi"), messageDelta("end_turn", 1), stop))
        h.events(request())
        val rec = h.take()
        assertNull(rec.getHeader("anthropic-beta"))
        assertFalse(rec.json().containsKey("fallbacks"))
    }

    @Test fun refusalIsNotPresentedAsAnswer() = runBlocking {
        h.enqueueSse(stream(start, delta(0, "I started to"), messageDelta("refusal", 3), stop))
        val detailed = h.events(request())
        assertEquals(StopReason.REFUSAL, (detailed.last() as EngineEvent.Completed).stop)

        h.enqueueSse(stream(start, delta(0, "I started to"), messageDelta("refusal", 3), stop))
        val public = h.engine.toAiEvents(h.engine.streamDetailed(request())).toList()
        val last = public.last()
        assertTrue(last is AiEvent.Error)
        assertEquals("The model declined this request.", (last as AiEvent.Error).message)
        assertTrue(public.none { it is AiEvent.Done })

        h.enqueueSse(stream(start, delta(0, "I started to"), messageDelta("refusal", 3), stop))
        try {
            h.engine.complete(request())
            fail("refusal must throw")
        } catch (e: AiException) {
            assertEquals("The model declined this request.", e.message)
        }
    }

    @Test fun fallbackServedResponseIsPricedAtTheServingModel() {
        // Sticky routing: message_start already names the fallback model (claude-opus-5: $5 / $25).
        val fallbackStart = start.replace("\"model\":\"claude-opus-5-5\"", "\"model\":\"claude-opus-5\"")
        h.enqueueSse(stream(fallbackStart, delta(0, "Hi"), messageDelta("end_turn", 9), stop))
        val done = h.events(request()).last() as EngineEvent.Completed
        assertEquals(25 * 5.0 / 1e6 + 9 * 25.0 / 1e6, done.usage.estimatedCostUsd!!, 1e-12)
        assertEquals("claude-opus-5", h.usage.rows.last().model)
    }

    @Test fun everyFallbackAttemptIsCountedAtItsOwnModel() {
        val fallbackStart = start.replace("\"model\":\"claude-opus-5-5\"", "\"model\":\"claude-opus-5\"")
        val finalDelta = sseEvent(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":9,""" +
                """"iterations":[{"type":"message","input_tokens":25,"output_tokens":4},{"type":"fallback_message","input_tokens":25,"output_tokens":9}]}}""",
        )
        h.enqueueSse(stream(fallbackStart, delta(0, "Hi"), finalDelta, stop))
        val done = h.events(request()).last() as EngineEvent.Completed
        // Declined attempt at Opus 5.5 ($4 / $20), serving attempt at Opus 5 ($5 / $25).
        assertEquals((25 * 4.0 + 4 * 20.0 + 25 * 5.0 + 9 * 25.0) / 1e6, done.usage.estimatedCostUsd!!, 1e-12)
        assertEquals(50, done.usage.inputTokens)
        assertEquals(13, done.usage.outputTokens)
        assertEquals("claude-opus-5", h.usage.rows.last().model)
    }

    @Test fun servedModelIsIgnoredWithoutFallbacks() {
        // claude-haiku-4-5 is not sent with fallbacks: the requested model's prices apply ($1 / $5).
        h.enqueueSse(stream(start, delta(0, "Hi"), messageDelta("end_turn", 9), stop))
        val done = h.events(request(ModelRef(AiProviderId.ANTHROPIC, "claude-haiku-4-5"))).last() as EngineEvent.Completed
        assertEquals(25 * 1.0 / 1e6 + 9 * 5.0 / 1e6, done.usage.estimatedCostUsd!!, 1e-12)
        assertEquals("claude-haiku-4-5", h.usage.rows.last().model)
    }

    @Test fun maxTokensStopIsReported() {
        h.enqueueSse(stream(start, delta(0, "Long answer"), messageDelta("max_tokens", 16000), stop))
        val done = h.events(request()).last() as EngineEvent.Completed
        assertEquals(StopReason.MAX_TOKENS, done.stop)
        assertEquals("Long answer", done.text)
    }

    @Test fun overloadedErrorEventBeforeTextIsRetried() {
        h.enqueueSse(stream(start, sseEvent("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")))
        h.enqueueSse(stream(start, delta(0, "OK"), messageDelta("end_turn", 1), stop))
        val events = h.events(request())
        assertEquals(1, events.filterIsInstance<EngineEvent.Retrying>().size)
        assertEquals("OK", (events.last() as EngineEvent.Completed).text)
        assertEquals(2, h.server.requestCount)
    }

    @Test fun errorEventAfterTextFailsWithPartial() {
        h.enqueueSse(stream(start, delta(0, "Half"), sseEvent("error", """{"type":"error","error":{"type":"api_error","message":"Internal"}}""")))
        val last = h.events(request()).last() as EngineEvent.Failed
        assertEquals("Half", last.partialText)
        assertTrue(last.failure.message.contains("Anthropic"))
        assertEquals(1, h.server.requestCount)
    }

    @Test fun utilityTasksUseLowEffortAndOlderModelsNone() {
        h.enqueueSse(stream(start, delta(0, "Hola"), messageDelta("end_turn", 1), stop))
        h.events(request(task = AiTask.TRANSLATE))
        assertEquals("low", h.take().json()["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        h.enqueueSse(stream(start, delta(0, "Hi"), messageDelta("end_turn", 1), stop))
        h.events(request(ModelRef(AiProviderId.ANTHROPIC, "claude-haiku-4-5")))
        val body = h.take().json()
        assertFalse(body.containsKey("output_config"))
        assertFalse(body.containsKey("fallbacks"))
    }

    @Test fun nonStreamedResponseIsDecoded() {
        h.enqueueJson("""{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"Whole answer."}],"stop_reason":"end_turn","usage":{"input_tokens":11,"output_tokens":3}}""")
        val done = h.events(request()).last() as EngineEvent.Completed
        assertEquals("Whole answer.", done.text)
        assertEquals(11, done.usage.inputTokens)
        assertEquals(3, done.usage.outputTokens)
    }

    @Test fun overloaded529IsRetryable() {
        h.enqueueJson("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""", code = 529)
        h.enqueueSse(stream(start, delta(0, "Back"), messageDelta("end_turn", 1), stop))
        val done = h.events(request()).last() as EngineEvent.Completed
        assertEquals("Back", done.text)
    }
}
