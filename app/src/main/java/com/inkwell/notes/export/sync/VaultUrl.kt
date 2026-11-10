package com.inkwell.notes.export.sync

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/**
 * A validated notes-vault base: `https://host[:port]/notes/<linkId>` (docs/VAULT_API.md).
 * Only [VaultUrl.parse] creates HTTPS endpoints; [insecureForTests] exists so JVM tests can talk
 * to a plain-HTTP MockWebServer.
 */
class VaultEndpoint private constructor(val base: HttpUrl) {
    /** Normalised base without a trailing slash. Contains the private link id: never log it. */
    val baseUrl: String = base.toString().trimEnd('/')

    /** `{BASE}/api/<segments...>`; every segment is percent-encoded. */
    fun api(vararg segments: String): HttpUrl =
        base.newBuilder().addPathSegment("api").apply { segments.forEach { addPathSegment(it) } }.build()

    /** Short stable fingerprint of [baseUrl] used to bind a cached session to this vault. */
    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(baseUrl.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    /** The same vault on another host of the same site (see [VaultRedirect]). */
    internal fun withHost(host: String): VaultEndpoint = VaultEndpoint(base.newBuilder().host(host).build())

    override fun equals(other: Any?): Boolean = other is VaultEndpoint && other.baseUrl == baseUrl
    override fun hashCode(): Int = baseUrl.hashCode()
    override fun toString(): String = "VaultEndpoint(${base.host})"

    companion object {
        internal fun of(url: HttpUrl): VaultEndpoint = VaultEndpoint(url)

        /** Plain-HTTP endpoint for tests against a local MockWebServer. Never used by the app. */
        internal fun insecureForTests(url: HttpUrl): VaultEndpoint = VaultEndpoint(url)
    }
}

/** Normalises and validates what the user typed or pasted as the vault link. */
object VaultUrl {
    const val PLACEHOLDER = "https://yourpitbox.com/notes/<your private link>"

    sealed interface Parsed {
        data class Valid(val endpoint: VaultEndpoint) : Parsed
        data class Invalid(val reason: String) : Parsed
        data object Empty : Parsed
    }

    /**
     * Trims, drops a query, fragment and trailing slashes, lower-cases scheme and host, and requires
     * exactly `https://host/notes/<linkId>`. A missing scheme is taken as https.
     */
    fun parse(raw: String): Parsed {
        var text = raw.trim()
        if (text.isEmpty()) return Parsed.Empty
        if (text.any { it.isWhitespace() }) return Parsed.Invalid("The vault link can't contain spaces.")
        text = text.substringBefore('#').substringBefore('?')
        val schemeEnd = text.indexOf("://")
        if (schemeEnd < 0) {
            text = "https://$text"
        } else {
            val scheme = text.substring(0, schemeEnd).lowercase()
            if (scheme == "http") return Parsed.Invalid("The vault link must start with https:// so your notes travel encrypted.")
            if (scheme != "https") return Parsed.Invalid("The vault link must start with https://.")
            text = "https" + text.substring(schemeEnd)
        }
        text = text.trimEnd('/')
        val url = text.toHttpUrlOrNull() ?: return Parsed.Invalid(FORMAT_HINT)
        if (url.host.isBlank() || url.username.isNotEmpty() || url.password.isNotEmpty()) return Parsed.Invalid(FORMAT_HINT)
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size != 2 || !segments[0].equals("notes", ignoreCase = true)) return Parsed.Invalid(FORMAT_HINT)
        val linkId = segments[1]
        if (linkId.isBlank() || linkId == "." || linkId == "..") return Parsed.Invalid(FORMAT_HINT)
        val normalized = HttpUrl.Builder()
            .scheme("https")
            .host(url.host)
            .port(url.port)
            .addPathSegment("notes")
            .addPathSegment(linkId)
            .build()
        return Parsed.Valid(VaultEndpoint.of(normalized))
    }

    /** The normalised link, or null when [raw] is empty or invalid. */
    fun normalize(raw: String): String? = (parse(raw) as? Parsed.Valid)?.endpoint?.baseUrl

    fun endpointOrNull(raw: String): VaultEndpoint? = (parse(raw) as? Parsed.Valid)?.endpoint

    const val FORMAT_HINT = "Use the full private link, like https://yourpitbox.com/notes/<your private link>."
}

/** Which redirects from the vault are followed. The client never follows one blindly: the token must not travel. Pure. */
object VaultRedirect {
    /**
     * The host to use instead of [request]'s when the answer is a permanent redirect (301/308) to
     * the same address with only a leading `www.` added or dropped: how a site moves between
     * `www.example.com` and `example.com`, both run by whoever owns example.com. Same scheme, port
     * and path; anything else (another site, another path, http, a temporary redirect) is null.
     */
    fun movedHost(request: HttpUrl, code: Int, location: String?): String? {
        if (code != 301 && code != 308) return null
        val target = location?.trim()?.takeIf { it.isNotEmpty() }?.let { request.resolve(it) } ?: return null
        if (target.scheme != request.scheme || target.port != request.port) return null
        if (target.username.isNotEmpty() || target.password.isNotEmpty()) return null
        if (target.encodedPath.trimEnd('/') != request.encodedPath.trimEnd('/')) return null
        val from = request.host
        val to = target.host
        return to.takeIf { it == "www.$from" || from == "www.$to" }
    }

    /** The host a redirect points at, for a message; null when [location] is not a URL. */
    fun targetHost(request: HttpUrl, location: String?): String? =
        location?.trim()?.takeIf { it.isNotEmpty() }?.let { request.resolve(it) }?.host
}
