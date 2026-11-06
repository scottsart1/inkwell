package com.inkwell.notes.ai

import com.inkwell.notes.ai.net.AiFailure
import com.inkwell.notes.ai.net.Endpoints
import com.inkwell.notes.ai.net.ErrorMapper
import com.inkwell.notes.ai.net.Redactor
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMappingTest {
    private val p = AiProviderId.OPENAI

    @Test fun authAndPermissionErrors() {
        val a = ErrorMapper.http(p, 401, """{"error":{"message":"Incorrect API key"}}""")
        assertEquals(AiFailure.Kind.AUTH, a.kind)
        assertFalse(a.retryable)
        assertTrue(a.message.startsWith("OpenAI rejected the API key"))
        val f = ErrorMapper.http(AiProviderId.ANTHROPIC, 403, """{"type":"error","error":{"type":"permission_error","message":"No access"}}""")
        assertEquals(AiFailure.Kind.FORBIDDEN, f.kind)
        assertTrue(f.message.contains("No access"))
    }

    @Test fun modelNotFound() {
        val f = ErrorMapper.http(p, 404, """{"error":{"message":"The model does not exist"}}""", modelId = "gpt-x")
        assertEquals(AiFailure.Kind.NOT_FOUND, f.kind)
        assertTrue(f.message.contains("\"gpt-x\" was not found at OpenAI"))
    }

    @Test fun contextTooLongAndBadRequest() {
        val c = ErrorMapper.http(p, 400, """{"error":{"message":"This model's maximum context length is 128000 tokens. However, your messages resulted in 130000 tokens."}}""")
        assertEquals(AiFailure.Kind.CONTEXT_TOO_LONG, c.kind)
        val anthropic = ErrorMapper.http(AiProviderId.ANTHROPIC, 400, """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 1200000 tokens > 1000000 maximum"}}""")
        assertEquals(AiFailure.Kind.CONTEXT_TOO_LONG, anthropic.kind)
        assertEquals(AiFailure.Kind.CONTEXT_TOO_LONG, ErrorMapper.http(p, 413, "").kind)
        val bad = ErrorMapper.http(p, 400, """{"error":{"message":"Invalid image URL"}}""")
        assertEquals(AiFailure.Kind.BAD_REQUEST, bad.kind)
        assertTrue(bad.message.contains("Invalid image URL"))
        assertFalse(bad.retryable)
    }

    @Test fun rateLimitsAreRetryableWithRetryAfter() {
        val r = ErrorMapper.http(p, 429, """{"error":{"message":"Rate limit reached for requests"}}""", retryAfterHeader = "7")
        assertTrue(r.retryable)
        assertEquals(7_000L, r.retryAfterMs)
        assertEquals(AiFailure.Kind.RATE_LIMIT, r.kind)
        val date = ErrorMapper.parseRetryAfter("Wed, 21 Oct 2026 07:28:10 GMT", nowMs = java.time.ZonedDateTime.parse("2026-10-21T07:28:00Z").toInstant().toEpochMilli())
        assertEquals(10_000L, date)
        assertNull(ErrorMapper.parseRetryAfter("soon"))
    }

    @Test fun quotaIsNotRetryable() {
        val q = ErrorMapper.http(p, 429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota"}}""")
        assertEquals(AiFailure.Kind.QUOTA, q.kind)
        assertFalse(q.retryable)
        assertEquals(AiFailure.Kind.QUOTA, ErrorMapper.http(AiProviderId.DEEPSEEK, 402, """{"error":{"message":"Insufficient Balance"}}""").kind)
        assertEquals(AiFailure.Kind.QUOTA, ErrorMapper.http(AiProviderId.ANTHROPIC, 400, """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low to access the Anthropic API."}}""").kind)
    }

    @Test fun rateLimitMentioningBillingStaysRetryable() {
        val body = """{"error":{"message":"Rate limit reached for gpt-4o-mini on requests per min (RPM): Limit 3, Used 3. Please try again in 20s. """ +
            """Visit https://platform.openai.com/account/rate-limits. You can increase your rate limit by adding a payment method to your account at """ +
            """https://platform.openai.com/account/billing.","type":"requests","code":"rate_limit_exceeded"}}"""
        val r = ErrorMapper.http(p, 429, body, retryAfterHeader = "20")
        assertEquals(AiFailure.Kind.RATE_LIMIT, r.kind)
        assertTrue(r.retryable)
        assertEquals(20_000L, r.retryAfterMs)
    }

    @Test fun inactiveBillingIsQuotaNotRateLimit() {
        val inactive = ErrorMapper.http(p, 429,
            """{"error":{"message":"Your account is not active, please check your billing details on our website.","type":"billing_not_active","code":"billing_not_active"}}""")
        assertEquals(AiFailure.Kind.QUOTA, inactive.kind)
        assertFalse(inactive.retryable)
        val hardLimit = ErrorMapper.http(p, 429,
            """{"error":{"message":"Billing hard limit has been reached","type":"invalid_request_error","code":"billing_hard_limit_reached"}}""")
        assertEquals(AiFailure.Kind.QUOTA, hardLimit.kind)
        assertFalse(hardLimit.retryable)
    }

    @Test fun nonPrimitiveErrorMessagesDoNotThrow() {
        assertNull(ErrorMapper.providerMessage("""{"error":{"message":{"code":1}}}"""))
        assertNull(ErrorMapper.providerMessage("""{"error":{"message":["a","b"]}}"""))
        assertNull(ErrorMapper.providerMessage("""{"error":[1,2],"message":{"x":1}}"""))
        assertEquals("plain", ErrorMapper.providerMessage("""{"error":"plain"}"""))
        assertEquals("top", ErrorMapper.providerMessage("""{"error":{"message":{"code":1}},"message":"top"}"""))
        assertEquals(AiFailure.Kind.BAD_REQUEST, ErrorMapper.http(p, 400, """{"error":{"message":{"code":1}}}""").kind)
    }

    @Test fun serverAndNetworkErrorsAreRetryable() {
        for (code in listOf(500, 502, 503, 504, 529)) assertTrue("$code", ErrorMapper.http(p, code, "").retryable)
        assertTrue(ErrorMapper.io(p, UnknownHostException("api.openai.com")).retryable)
        assertTrue(ErrorMapper.io(p, SocketTimeoutException("timeout")).message.contains("took too long"))
        assertTrue(ErrorMapper.io(p, IOException("unexpected end of stream")).retryable)
        assertEquals(AiFailure.Kind.NETWORK, ErrorMapper.io(p, IOException("x")).kind)
    }

    @Test fun streamErrorTypes() {
        assertTrue(ErrorMapper.streamError(AiProviderId.ANTHROPIC, "overloaded_error", "Overloaded").retryable)
        assertTrue(ErrorMapper.streamError(AiProviderId.ANTHROPIC, "api_error", "Internal").retryable)
        assertFalse(ErrorMapper.streamError(AiProviderId.ANTHROPIC, "invalid_request_error", "bad").retryable)
        assertEquals(AiFailure.Kind.AUTH, ErrorMapper.streamError(AiProviderId.ANTHROPIC, "authentication_error", "bad key").kind)
    }

    @Test fun keysAreRedactedEverywhere() {
        val key = "sk-proj-abcdefghijklmnop1234"
        val f = ErrorMapper.http(p, 400, """{"error":{"message":"Bad key $key supplied"}}""", apiKey = key)
        assertFalse(f.message.contains(key))
        assertTrue(f.message.contains("••••"))
        assertFalse(ErrorMapper.io(p, IOException("failed with $key"), key).message.contains(key))
        assertEquals("Authorization: Bearer ••••", Redactor.redact("Authorization: Bearer abc.def-ghijklmnop"))
        assertFalse(Redactor.redact("key sk-ant-api03-XYZXYZXYZXYZ").contains("XYZXYZ"))
        assertEquals("x-api-key: ••••", Redactor.redact("x-api-key: 0123456789abcdef"))
    }

    @Test fun customEndpointsMustBeHttps() {
        assertEquals("https://proxy.example.com/v1", Endpoints.normalizeCustom("https://proxy.example.com/v1/").getOrThrow())
        assertTrue(Endpoints.normalizeCustom("http://proxy.example.com/v1").exceptionOrNull() is AiException)
        assertTrue(Endpoints.normalizeCustom("not a url").isFailure)
        assertTrue(Endpoints.normalizeCustom("https://x.example.com/v1?key=1").isFailure)
        assertEquals("https://api.openai.com/v1", Endpoints.resolve("https://api.openai.com/v1/", "  "))
        assertEquals("https://api.moonshot.cn/v1", Endpoints.resolve("https://api.moonshot.ai/v1", "https://api.moonshot.cn/v1"))
    }

    @Test fun aForbiddenCheckClearsOnlyTheStale403Flag() {
        // A 403 on the key check (possibly a region or proxy block) must not clear a flag a 401 set.
        val rejected = ErrorMapper.http(p, 401, """{"error":{"message":"Incorrect API key"}}""").message
        assertFalse(AiServiceImpl.isStaleForbiddenFlag(rejected))
        // Older builds stored the 403 message as "Invalid": that one is cleared.
        val forbidden = ErrorMapper.http(p, 403, """{"error":{"message":"unsupported_country_region_territory"}}""").message
        assertTrue(AiServiceImpl.isStaleForbiddenFlag(forbidden))
        assertTrue(AiServiceImpl.isStaleForbiddenFlag("Anthropic refused access to this model."))
        assertFalse(AiServiceImpl.isStaleForbiddenFlag(null))
    }
}
