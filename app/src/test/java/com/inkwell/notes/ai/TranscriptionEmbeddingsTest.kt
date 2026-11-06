package com.inkwell.notes.ai

import com.inkwell.notes.ai.provider.TranscriptionParser
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptionEmbeddingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val h = EngineHarness(prefs = AiPrefs(semanticSearch = true))

    @After fun tearDown() = h.close()

    private fun segment(name: String): File = tmp.newFile(name).apply { writeBytes(ByteArray(512) { it.toByte() }) }

    private fun verbose(vararg segs: Triple<Double, Double, String>) =
        """{"task":"transcribe","language":"english","duration":120.0,"text":"${segs.joinToString(" ") { it.third }}","segments":[""" +
            segs.mapIndexed { i, (s, e, t) -> """{"id":$i,"seek":0,"start":$s,"end":$e,"text":" $t","tokens":[],"temperature":0.0}""" }.joinToString(",") +
            "]}"

    @Test fun parserOffsetsSegmentTimestamps() {
        val parsed = TranscriptionParser.parse(verbose(Triple(0.0, 4.25, "Hello"), Triple(4.25, 9.5, "class")), offsetMs = 120_000, durationMs = 120_000)
        assertEquals(listOf(120_000L, 124_250L), parsed.segments.map { it.startMs })
        assertEquals(listOf(124_250L, 129_500L), parsed.segments.map { it.endMs })
        assertEquals(listOf("Hello", "class"), parsed.segments.map { it.text })
        assertEquals("english", parsed.language)
        assertTrue(parsed.segments.all { it.speaker == null })
    }

    @Test fun textOnlyResponseBecomesOneSegmentPerFile() {
        val parsed = TranscriptionParser.parse("""{"text":"Whole file text."}""", offsetMs = 60_000, durationMs = 30_000)
        val seg = parsed.segments.single()
        assertEquals(60_000L, seg.startMs)
        assertEquals(90_000L, seg.endMs)
        assertEquals("Whole file text.", seg.text)
        assertTrue(TranscriptionParser.parse("""{"text":"  "}""", 0, 1000).segments.isEmpty())
        assertEquals("en", TranscriptionParser.isoLanguage("en-US"))
        assertNull(TranscriptionParser.isoLanguage(null))
    }

    @Test fun whisperTranscribesSegmentsInOrderWithCumulativeOffsets() = runBlocking {
        h.enqueueJson(verbose(Triple(0.0, 2.0, "First"), Triple(2.0, 5.0, "part")))
        h.enqueueJson(verbose(Triple(0.5, 3.0, "Second")))
        val files = listOf(segment("seg-000.m4a"), segment("seg-001.m4a"))
        val t = h.engine.transcribe("rec1", files, listOf(120_000L, 45_000L), language = "en-GB")!!
        assertEquals("openai:whisper-1", t.source)
        assertEquals("rec1", t.recordingId)
        assertEquals("en-GB", t.language)
        assertEquals(listOf(0L, 2_000L, 120_500L), t.segments.map { it.startMs })
        assertEquals(listOf("First", "part", "Second"), t.segments.map { it.text })

        val r1 = h.take()
        assertEquals("/v1/audio/transcriptions", r1.path)
        assertEquals("Bearer sk-test-openai-0000000000", r1.getHeader("Authorization"))
        val body1 = r1.body.readUtf8()
        assertTrue(body1.contains("name=\"model\"") && body1.contains("\r\n\r\nwhisper-1\r\n"))
        assertTrue(body1.contains("verbose_json"))
        assertTrue(body1.contains("name=\"timestamp_granularities[]\""))
        assertTrue(body1.contains("filename=\"seg-000.m4a\""))
        assertTrue(body1.contains("audio/mp4"))
        assertTrue(body1.contains("name=\"language\"") && body1.contains("\r\n\r\nen\r\n"))
        assertTrue(h.take().body.readUtf8().contains("filename=\"seg-001.m4a\""))

        val row = h.usage.rows.single()
        assertEquals(AiTask.TRANSCRIPTION.name, row.task)
        assertEquals(0.006 * (165_000 / 60_000.0), row.estimatedCostUsd!!, 1e-9)
    }

    @Test fun gpt4oTranscribeUsesJsonAndOneSegmentPerFile() = runBlocking {
        h.prefs = AiPrefs(routing = mapOf(AiTask.TRANSCRIPTION to ModelRef(AiProviderId.OPENAI, "gpt-4o-transcribe")))
        h.enqueueJson("""{"text":"Alpha."}""")
        h.enqueueJson("""{"text":"Beta."}""")
        val t = h.engine.transcribe("r", listOf(segment("a.m4a"), segment("b.m4a")), listOf(10_000L, 20_000L), null)!!
        assertEquals("openai:gpt-4o-transcribe", t.source)
        assertEquals(listOf(0L to 10_000L, 10_000L to 30_000L), t.segments.map { it.startMs to it.endMs })
        val body = h.take().body.readUtf8()
        assertTrue(body.contains("gpt-4o-transcribe"))
        assertFalse(body.contains("verbose_json"))
        assertFalse(body.contains("timestamp_granularities"))
    }

    @Test fun missingSegmentStillAdvancesTimeline() = runBlocking {
        h.enqueueJson(verbose(Triple(1.0, 2.0, "After gap")))
        val missing = File(tmp.root, "missing.m4a")
        val t = h.engine.transcribe("r", listOf(missing, segment("ok.m4a")), listOf(30_000L, 30_000L), null)!!
        assertEquals(31_000L, t.segments.single().startMs)
        assertEquals(1, h.server.requestCount)
    }

    @Test fun transcriptionUnavailableWithoutOpenAiKey() = runBlocking {
        h.keys.keys.remove(AiProviderId.OPENAI)
        assertNull(h.engine.transcribe("r", listOf(segment("x.m4a")), listOf(1000L), null))
        assertNull(h.engine.embed(listOf("a")))
    }

    @Test fun embeddingsBatchedBy64InOrder() = runBlocking {
        fun response(n: Int, offset: Int) = """{"object":"list","data":[""" +
            (0 until n).reversed().joinToString(",") { i -> """{"object":"embedding","index":$i,"embedding":[${offset + i}.0, 0.5]}""" } +
            """],"model":"text-embedding-3-small","usage":{"prompt_tokens":${n * 3},"total_tokens":${n * 3}}}"""
        h.enqueueJson(response(64, 0))
        h.enqueueJson(response(36, 64))
        val texts = (0 until 100).map { "text $it" }
        val vectors = h.engine.embed(texts)!!
        assertEquals(100, vectors.size)
        assertArrayEquals(floatArrayOf(0f, 0.5f), vectors[0], 0f)
        assertArrayEquals(floatArrayOf(99f, 0.5f), vectors[99], 0f)
        val first = Json.parseToJsonElement(h.take().body.readUtf8()).jsonObject
        assertEquals("text-embedding-3-small", first["model"]!!.jsonPrimitive.content)
        assertEquals(64, first["input"]!!.jsonArray.size)
        assertEquals(36, Json.parseToJsonElement(h.take().body.readUtf8()).jsonObject["input"]!!.jsonArray.size)
        val row = h.usage.rows.single()
        assertEquals(300, row.inputTokens)
        assertEquals(300 * 0.02 / 1e6, row.estimatedCostUsd!!, 1e-12)
    }

    @Test fun embeddingsOffWithoutSemanticSearch() = runBlocking {
        h.prefs = AiPrefs(semanticSearch = false)
        assertNull(h.engine.embed(listOf("a")))
        assertEquals(0, h.server.requestCount)
    }

    @Test fun validateKeyCallsModelsEndpoint() = runBlocking {
        h.enqueueJson("""{"data":[{"id":"gpt-4o"},{"id":"whisper-1"},{"id":"tts-1"}]}""")
        val ok = h.engine.validateKey(AiProviderId.OPENAI, " sk-new-key-123456789 ", null)
        assertTrue(ok.isSuccess)
        assertEquals("Key works. 2 models available.", ok.getOrThrow())
        val rec = h.take()
        assertEquals("/v1/models", rec.path)
        assertEquals("GET", rec.method)
        assertEquals("Bearer sk-new-key-123456789", rec.getHeader("Authorization"))
        assertEquals(listOf("gpt-4o", "whisper-1", "tts-1"), h.models.liveIds(AiProviderId.OPENAI))

        h.enqueueJson("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""", code = 401)
        val check = h.engine.checkKey(AiProviderId.ANTHROPIC, "sk-ant-bad-123456", null)
        assertFalse(check.ok)
        assertTrue(check.rejected)
        assertFalse(check.authenticated)
        assertTrue(check.message.contains("Anthropic rejected the API key"))
        val anth = h.take()
        assertEquals("sk-ant-bad-123456", anth.getHeader("x-api-key"))
        assertEquals("2023-06-01", anth.getHeader("anthropic-version"))
        assertEquals("auth failures are not retried", 2, h.server.requestCount)
    }

    @Test fun forbiddenModelListDoesNotRejectKey() = runBlocking {
        // OpenAI restricted keys without the model.read scope get 403 on /models but still chat.
        h.enqueueJson("""{"error":{"message":"Missing scopes: api.model.read","type":"invalid_request_error"}}""", code = 403)
        val check = h.engine.checkKey(AiProviderId.OPENAI, "sk-restricted-123456789", null)
        assertFalse(check.ok)
        assertFalse(check.rejected)
        assertTrue(check.authenticated)
        assertTrue(check.message.contains("403"))
    }
}
