package com.inkwell.notes.ai

import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiProviderId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Key validation must only succeed on an actual model list, not on any 200 (Wi-Fi sign-in pages, websites). */
class KeyCheckResponseTest {
    private val h = EngineHarness()

    @After fun tearDown() = h.close()

    private fun enqueueHtml() {
        h.server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
                .setBody("<!doctype html><html><head><title>Sign in to Hotel Wi-Fi</title></head><body>Accept the terms</body></html>"),
        )
    }

    @Test fun signInPageIsNotAWorkingKey() = runBlocking {
        enqueueHtml()
        val check = h.engine.checkKey(AiProviderId.OPENAI, "sk-test-0000000000000000", null)
        assertFalse(check.ok)
        assertFalse("the key itself was not refused", check.rejected)
        assertTrue(check.message, check.message.contains("model list"))
        assertEquals("not retried", 1, h.server.requestCount)
        enqueueHtml()
        assertFalse(h.engine.validateKey(AiProviderId.OPENAI, "sk-test-0000000000000000", null).isSuccess)
    }

    @Test fun signInPageNeverReplacesTheCachedModelList() = runBlocking {
        h.enqueueJson("""{"object":"list","data":[{"id":"gpt-4o"},{"id":"gpt-4.1-mini"}]}""")
        assertTrue(h.engine.checkKey(AiProviderId.OPENAI, "sk-test-0000000000000000", null).ok)
        assertEquals(listOf("gpt-4o", "gpt-4.1-mini"), h.models.liveIds(AiProviderId.OPENAI))

        enqueueHtml()
        try {
            h.engine.models(AiProviderId.OPENAI, refresh = true)
            fail("a refresh that got a web page must report it")
        } catch (e: AiException) {
            assertTrue(e.message!!.contains("model list"))
        }
        assertEquals(listOf("gpt-4o", "gpt-4.1-mini"), h.models.liveIds(AiProviderId.OPENAI))
    }

    @Test fun anyJsonListShapeStillValidates() = runBlocking {
        h.enqueueJson("""{"object":"list","data":[]}""")
        assertTrue(h.engine.checkKey(AiProviderId.DEEPSEEK, "sk-test-0000000000000000", null).ok)
    }
}
