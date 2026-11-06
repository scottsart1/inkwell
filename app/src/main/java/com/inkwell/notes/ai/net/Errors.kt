package com.inkwell.notes.ai.net

import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException

/** A classified failure with a message that is safe to show (no keys, no note content). */
data class AiFailure(
    val message: String,
    val retryable: Boolean,
    /** Server-requested wait before retrying, if any. */
    val retryAfterMs: Long? = null,
    val httpStatus: Int? = null,
    val kind: Kind = Kind.OTHER,
) {
    enum class Kind { AUTH, FORBIDDEN, NOT_FOUND, BAD_REQUEST, CONTEXT_TOO_LONG, RATE_LIMIT, QUOTA, SERVER, NETWORK, REFUSAL, LIMIT, CONFIG, OTHER }

    fun toException(cause: Throwable? = null) = AiException(message, retryable, cause)
}

internal val LenientJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/** Removes credentials from any text that might reach the UI or a log. */
object Redactor {
    private val patterns = listOf(
        Regex("""sk-ant-[A-Za-z0-9_\-]{8,}"""),
        Regex("""sk-[A-Za-z0-9_\-]{12,}"""),
        Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]{8,}"""),
        Regex("""(?i)(x-api-key["']?\s*[:=]\s*["']?)[A-Za-z0-9._\-]{8,}"""),
        // Inline media a provider may echo back in a validation error (voice reference clips, page images).
        Regex("""(data:[A-Za-z]+/[A-Za-z0-9.+\-]+;base64,)[A-Za-z0-9+/=]{8,}"""),
    )

    fun redact(text: String?, vararg secrets: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out: String = text
        for (s in secrets) if (!s.isNullOrBlank() && s.length >= 4) out = out.replace(s, "••••")
        for (p in patterns) {
            out = p.replace(out) { m -> if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] + "••••" else "••••" }
        }
        return out
    }
}

/** Maps HTTP statuses, provider error bodies and I/O exceptions to friendly [AiFailure]s. */
object ErrorMapper {
    private const val MAX_DETAIL = 220

    fun http(
        provider: AiProviderId,
        status: Int,
        body: String?,
        retryAfterHeader: String? = null,
        modelId: String? = null,
        apiKey: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): AiFailure {
        val name = provider.displayName
        val detail = providerMessage(body)?.let { Redactor.redact(it, apiKey) }?.take(MAX_DETAIL)
        val lower = (detail ?: "").lowercase() + " " + (body ?: "").take(2000).lowercase()
        val retryAfter = parseRetryAfter(retryAfterHeader, nowMs)
        val model = modelId?.let { "\"$it\"" } ?: "the selected model"
        return when {
            status == 401 -> AiFailure(
                "$name rejected the API key. Check or replace it in Settings > AI providers.",
                retryable = false, httpStatus = status, kind = AiFailure.Kind.AUTH,
            )
            status == 403 -> AiFailure(
                "$name refused access (403). The key may not have permission for $model, or the service is not available in your region." +
                    (detail?.let { " Details: $it" } ?: ""),
                retryable = false, httpStatus = status, kind = AiFailure.Kind.FORBIDDEN,
            )
            status == 404 -> AiFailure(
                if (modelId != null) "Model $model was not found at $name. Choose another model in AI settings."
                else "$name endpoint not found (404). Check the custom endpoint URL in AI settings.",
                retryable = false, httpStatus = status, kind = AiFailure.Kind.NOT_FOUND,
            )
            status in 300..399 -> AiFailure(
                "$name endpoint redirected to another address ($status). Enter the final URL as the custom endpoint in AI settings.",
                retryable = false, httpStatus = status, kind = AiFailure.Kind.CONFIG,
            )
            status == 402 || isQuota(lower) -> AiFailure(
                "Your $name account has no remaining credit or quota. Add billing credit with $name, then try again.",
                retryable = false, httpStatus = status, kind = AiFailure.Kind.QUOTA,
            )
            status == 413 || ((status == 400 || status == 422) && isContextTooLong(lower)) -> AiFailure(
                "This request is too long for $model's context window. Select less content, or turn on " +
                    "\"Retrieval for large documents\" in AI settings.",
                retryable = false, httpStatus = status, kind = AiFailure.Kind.CONTEXT_TOO_LONG,
            )
            status == 400 || status == 422 -> AiFailure(
                "$name could not process the request" + (detail?.let { ": $it" } ?: " (bad request)."),
                retryable = false, httpStatus = status, kind = AiFailure.Kind.BAD_REQUEST,
            )
            status == 429 -> AiFailure(
                "$name is rate limiting requests. " +
                    (retryAfter?.let { "Try again in ${(it + 999) / 1000} s." } ?: "Wait a moment and try again."),
                retryable = true, retryAfterMs = retryAfter, httpStatus = status, kind = AiFailure.Kind.RATE_LIMIT,
            )
            status == 529 || status == 503 -> AiFailure(
                "$name is overloaded right now ($status). Try again in a moment.",
                retryable = true, retryAfterMs = retryAfter, httpStatus = status, kind = AiFailure.Kind.SERVER,
            )
            status in 500..599 -> AiFailure(
                "$name had a server error ($status). Try again in a moment.",
                retryable = true, retryAfterMs = retryAfter, httpStatus = status, kind = AiFailure.Kind.SERVER,
            )
            else -> AiFailure(
                "$name returned an unexpected response ($status)" + (detail?.let { ": $it" } ?: "."),
                retryable = false, httpStatus = status, kind = AiFailure.Kind.OTHER,
            )
        }
    }

    fun io(provider: AiProviderId, e: Throwable, apiKey: String? = null): AiFailure {
        val name = provider.displayName
        return when (e) {
            is UnknownHostException -> AiFailure(
                "Could not reach $name. Check your internet connection (or the custom endpoint host).",
                retryable = true, kind = AiFailure.Kind.NETWORK,
            )
            is SocketTimeoutException -> AiFailure("$name took too long to respond. Try again.", retryable = true, kind = AiFailure.Kind.NETWORK)
            is ConnectException -> AiFailure("Could not connect to $name. Check your connection and try again.", retryable = true, kind = AiFailure.Kind.NETWORK)
            is SSLException -> AiFailure(
                "A secure connection to $name could not be established. Check the device date and time and your network.",
                retryable = true, kind = AiFailure.Kind.NETWORK,
            )
            is InterruptedIOException, is IOException -> AiFailure(
                "The connection to $name was interrupted" +
                    (Redactor.redact(e.message, apiKey).takeIf { it.isNotBlank() }?.let { " ($it)." } ?: ".") + " Try again.",
                retryable = true, kind = AiFailure.Kind.NETWORK,
            )
            is AiException -> AiFailure(Redactor.redact(e.message, apiKey), e.retryable)
            else -> AiFailure(
                "Something went wrong talking to $name: " + Redactor.redact(e.message ?: e.javaClass.simpleName, apiKey).take(MAX_DETAIL),
                retryable = false,
            )
        }
    }

    /** Error raised inside a stream (OpenAI `{"error":...}` chunk, Anthropic `event: error`). */
    fun streamError(provider: AiProviderId, type: String?, message: String?, apiKey: String? = null): AiFailure {
        val name = provider.displayName
        val t = (type ?: "").lowercase()
        val msg = Redactor.redact(message, apiKey).take(MAX_DETAIL)
        return when {
            t.contains("overloaded") -> AiFailure("$name is overloaded right now. Try again in a moment.", retryable = true, kind = AiFailure.Kind.SERVER)
            t.contains("rate_limit") -> AiFailure("$name is rate limiting requests. Wait a moment and try again.", retryable = true, kind = AiFailure.Kind.RATE_LIMIT)
            t.contains("api_error") || t.contains("server_error") || t.contains("internal") ->
                AiFailure("$name had a server error. Try again in a moment.", retryable = true, kind = AiFailure.Kind.SERVER)
            t.contains("authentication") -> AiFailure("$name rejected the API key. Check it in AI settings.", retryable = false, kind = AiFailure.Kind.AUTH)
            t.contains("permission") -> AiFailure("$name refused access to this model.", retryable = false, kind = AiFailure.Kind.FORBIDDEN)
            t.contains("not_found") -> AiFailure("The selected model was not found at $name.", retryable = false, kind = AiFailure.Kind.NOT_FOUND)
            isContextTooLong(msg.lowercase()) -> AiFailure(
                "This request is too long for the model's context window. Select less content.",
                retryable = false, kind = AiFailure.Kind.CONTEXT_TOO_LONG,
            )
            else -> AiFailure("$name reported an error" + (if (msg.isNotBlank()) ": $msg" else "."), retryable = false)
        }
    }

    /** Extracts `error.message` (OpenAI/DeepSeek/Moonshot/Anthropic) or `message` from an error body. */
    fun providerMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val obj = runCatching { LenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return body.trim().takeIf { it.length in 1..300 && !it.startsWith("<") }
        val err = obj["error"]
        val msg = when (err) {
            // A non-primitive message (object/array from a custom endpoint) is ignored rather than thrown.
            is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
            null -> null
            else -> (err as? JsonPrimitive)?.contentOrNull
        } ?: (obj["message"] as? JsonPrimitive)?.contentOrNull
        return msg?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun isContextTooLong(lower: String): Boolean =
        listOf(
            "context length", "context_length", "maximum context", "context window", "too many tokens",
            "prompt is too long", "input is too long", "exceeds the maximum", "too long", "reduce the length",
            "maximum number of tokens", "token limit", "input length",
        ).any { lower.contains(it) }

    private fun isQuota(lower: String): Boolean =
        // No bare "billing": OpenAI rate-limit (429) bodies link to account/billing and must stay retryable.
        // OpenAI's inactive-billing errors (also 429, never retryable) are named explicitly instead.
        listOf(
            "insufficient_quota", "insufficient balance", "insufficient_balance", "credit balance is too low", "exceeded your current quota",
            "billing_not_active", "billing_hard_limit_reached", "check your billing details",
        ).any { lower.contains(it) }

    /** Retry-After as seconds or HTTP date, in ms. */
    fun parseRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
        return runCatching {
            val at: Instant = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (at.toEpochMilli() - nowMs).coerceAtLeast(0)
        }.getOrNull()
    }
}

/** Base URL handling: provider defaults, custom endpoints (https only), normalisation. */
object Endpoints {
    /**
     * Returns the effective base URL without a trailing slash. Throws [AiException] for a custom
     * URL that is malformed or not https (when [requireHttps]).
     */
    fun resolve(defaultBase: String, custom: String?, requireHttps: Boolean = true): String {
        val c = custom?.trim().orEmpty()
        if (c.isEmpty()) return defaultBase.trimEnd('/')
        return normalizeCustom(c, requireHttps).getOrElse { throw it }
    }

    fun normalizeCustom(url: String, requireHttps: Boolean = true): Result<String> {
        val c = url.trim()
        if (requireHttps && !c.startsWith("https://", ignoreCase = true)) {
            return Result.failure(AiException("Custom endpoints must start with https:// so your key and notes stay encrypted in transit."))
        }
        val parsed = c.toHttpUrlOrNull() ?: return Result.failure(AiException("\"$c\" is not a valid URL."))
        if (requireHttps && !parsed.isHttps) return Result.failure(AiException("Custom endpoints must use https://."))
        if (parsed.querySize > 0 || parsed.fragment != null) return Result.failure(AiException("Enter the endpoint base URL without query parameters."))
        return Result.success(c.trimEnd('/'))
    }
}
