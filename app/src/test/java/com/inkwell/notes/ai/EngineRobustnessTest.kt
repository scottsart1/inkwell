package com.inkwell.notes.ai

import com.inkwell.notes.ai.engine.EngineEvent
import com.inkwell.notes.ai.net.AiFailure
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Failure paths that must degrade into a clear [EngineEvent.Failed], never a crash or a hang. */
class EngineRobustnessTest {
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    private fun request(model: ModelRef, text: String = "Hello?") =
        AiRequest(AiTask.CHAT, listOf(AiMessage(AiRole.USER, text)), system = "Be brief.", model = model)

    /**
     * Keys copied from web pages or mail often carry an invisible character (zero-width space,
     * soft hyphen). OkHttp refuses such a header value with an IllegalArgumentException whose
     * message, for non-Authorization headers like Anthropic's x-api-key, contains the full key.
     * The engine must turn this into a failure the user can act on instead of crashing the panel.
     */
    @Test fun keyWithInvisibleCharactersFailsCleanlyInsteadOfThrowing() {
        val badOpenAi = "sk-test​openai-00000000"
        val badAnthropic = "sk-ant-test­anthropic-0000"
        h.keys.keys[AiProviderId.OPENAI] = badOpenAi
        h.keys.keys[AiProviderId.ANTHROPIC] = badAnthropic

        val openAi = h.events(request(ModelRef(AiProviderId.OPENAI, "gpt-4o")))
        val f1 = openAi.last() as EngineEvent.Failed
        assertEquals(AiFailure.Kind.CONFIG, f1.failure.kind)
        assertFalse(f1.failure.retryable)
        assertFalse(f1.failure.message, f1.failure.message.contains("openai-00000000"))
        assertTrue(f1.failure.message, f1.failure.message.contains("OpenAI"))

        val anthropic = h.events(request(ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5")))
        val f2 = anthropic.last() as EngineEvent.Failed
        assertEquals(AiFailure.Kind.CONFIG, f2.failure.kind)
        assertFalse(f2.failure.message, f2.failure.message.contains("anthropic-0000"))

        assertEquals("nothing was sent", 0, h.server.requestCount)
        assertTrue(h.usage.rows.isEmpty())
    }

    /** A redirect would replay x-api-key and the prompt to another host: it is reported, never followed. */
    @Test fun redirectsAreNotFollowed() {
        h.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", h.server.url("/elsewhere/v1/messages").toString()))
        val events = h.events(request(ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5")))
        val failed = events.last() as EngineEvent.Failed
        assertEquals(AiFailure.Kind.CONFIG, failed.failure.kind)
        assertFalse(failed.failure.retryable)
        assertTrue(failed.failure.message, failed.failure.message.contains("307"))
        assertEquals("the redirect target was not requested", 1, h.server.requestCount)
    }

    /** Saving a key strips the invisible characters and whitespace that copying adds, so the stored key is usable. */
    @Test fun savedKeysAreCleanedOfInvisibleCharactersAndWhitespace() {
        assertEquals("sk-ant-abc123DEF", AiServiceImpl.sanitizeKey(" sk-ant-abc​123­DEF⁠\n"))
        assertEquals("sk-abc", AiServiceImpl.sanitizeKey("sk- abc\t"))
        assertEquals("", AiServiceImpl.sanitizeKey(" ​ "))
        assertTrue(com.inkwell.notes.ai.engine.AiEngine.headerSafe(AiServiceImpl.sanitizeKey("sk-test​openai-00000000")))
        // Printable ASCII survives untouched.
        val key = "sk-proj-AbC_123-xyz.456~!#%"
        assertEquals(key, AiServiceImpl.sanitizeKey(key))
    }

    @Test fun keyWithInvisibleCharactersIsRejectedByValidationWithoutLeakingIt() = runBlocking {
        val bad = "sk-ant-test​anthropic-0000"
        val check = h.engine.checkKey(AiProviderId.ANTHROPIC, bad, null)
        assertFalse(check.ok)
        assertFalse(check.message, check.message.contains("anthropic-0000"))
        assertEquals(0, h.server.requestCount)
    }

    @Test fun transcriptionAndEmbeddingsWithBrokenKeyFailWithoutThrowingRawExceptions() = runBlocking {
        h.keys.keys[AiProviderId.OPENAI] = "sk-test​openai-00000000"
        h.prefs = h.prefs.copy(semanticSearch = true)
        val audio = java.io.File.createTempFile("seg", ".m4a").apply { writeBytes(ByteArray(64)); deleteOnExit() }
        for (block in listOf<suspend () -> Any?>({ h.engine.transcribe("r", listOf(audio), listOf(1_000L), null) }, { h.engine.embed(listOf("x")) })) {
            try {
                block()
                org.junit.Assert.fail("expected AiException")
            } catch (e: com.inkwell.notes.core.model.AiException) {
                assertFalse(e.message!!, e.message!!.contains("openai-00000000"))
            }
        }
        assertEquals(0, h.server.requestCount)
    }

    /** Pressing Stop while the provider has not even answered must not wait for the response. */
    @Test fun cancellingBeforeHeadersArriveReturnsPromptly() = runBlocking {
        h.server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(openAiSse("""{"choices":[{"delta":{"content":"late"},"finish_reason":"stop"}]}"""))
                .setHeadersDelay(3, TimeUnit.SECONDS),
        )
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<EngineEvent>()
        val job = launch(Dispatchers.IO) {
            h.engine.streamDetailed(request(ModelRef(AiProviderId.OPENAI, "gpt-4o"))).collect { e -> events += e; if (e is EngineEvent.Started) started.complete(Unit) }
        }
        withTimeout(5_000) { started.await() }
        delay(200)
        val t0 = System.nanoTime()
        withTimeout(5_000) { job.cancelAndJoin() }
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel took $tookMs ms", tookMs < 1_500)
        assertTrue(events.none { it is EngineEvent.Text || it is EngineEvent.Completed })
        assertTrue("nothing generated, nothing billed", h.usage.rows.isEmpty())
    }

    /** Anthropic rejects input + max_tokens beyond the context window; the budget must leave room for the input. */
    @Test fun anthropicMaxTokensLeavesRoomForTheInput() {
        // claude-haiku-4-5: 200k context. ~190k tokens of input leaves under 10k for output.
        val big = "word ".repeat(190_000 * 4 / 5)
        h.enqueueSse(
            sseEvent("message_start", """{"type":"message_start","message":{"usage":{"input_tokens":190000}}}""") +
                sseEvent("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""") +
                sseEvent("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""") +
                sseEvent("message_stop", """{"type":"message_stop"}"""),
        )
        val done = h.events(request(ModelRef(AiProviderId.ANTHROPIC, "claude-haiku-4-5"), big)).last()
        assertTrue(done.toString(), done is EngineEvent.Completed)
        val body = h.take().json()
        val maxTokens = body["max_tokens"]!!.jsonPrimitive.int
        val estimatedInput = (done as EngineEvent.Completed).usage.inputTokens
        assertTrue("max_tokens $maxTokens must fit next to ~$estimatedInput input tokens in 200k", maxTokens in 256..(200_000 - 190_000))
    }
}
