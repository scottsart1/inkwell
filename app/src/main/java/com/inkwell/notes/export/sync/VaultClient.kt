package com.inkwell.notes.export.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException

/** Where the vault password and the cached session live (SecretStore in the app, memory in tests). */
interface VaultCredentials {
    fun password(): String?
    fun session(): VaultSession?
    fun saveSession(session: VaultSession?)
}

/**
 * HTTP client for the notes vault (docs/VAULT_API.md). Logs in with the password from
 * [credentials], caches the bearer token, and on a `401` logs in again exactly once.
 * Never logs or puts into exception messages the link, password, token or any body.
 */
class VaultClient(
    http: OkHttpClient,
    endpoint: VaultEndpoint,
    private val credentials: VaultCredentials,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The vault answered from another host of its site ([VaultRedirect]); [endpoint] is now [to]. */
    private val onMoved: (from: VaultEndpoint, to: VaultEndpoint) -> Unit = { _, _ -> },
) {
    @Volatile private var current: VaultEndpoint = endpoint

    /** Where requests go: the endpoint given, or where the vault moved to since. */
    val endpoint: VaultEndpoint get() = current

    // Redirects are handled in [execute]; OkHttp following them would send the token anywhere.
    private val client: OkHttpClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val loginLock = Mutex()

    /** `POST api/session`; returns the session without caching it. */
    suspend fun login(password: String): VaultSession {
        val body = VaultJson.encodeToString(VaultLoginRequest.serializer(), VaultLoginRequest(password)).toRequestBody(JSON)
        val request = Request.Builder().url(endpoint.api("session")).post(body).header("Accept", "application/json").build()
        return execute(request).use { r ->
            when {
                r.isSuccessful -> {
                    val parsed = decode(r, VaultLoginResponse.serializer())
                    if (parsed.token.isBlank()) throw VaultException.BadResponse()
                    VaultSession(parsed.token, parsed.expiresAt)
                }
                r.code == 401 -> throw VaultException.WrongPassword()
                else -> throw errorFor(r, what = null)
            }
        }
    }

    /** Logs in with the stored password and caches the session. */
    suspend fun refreshSession(): VaultSession = loginLock.withLock { freshLogin() }

    /** `GET api/ping` (authorised); returns the vault's display name. */
    suspend fun ping(): String = authorized(
        build = { token -> Request.Builder().url(endpoint.api("ping")).get().bearer(token).build() },
    ) { r -> decode(r, VaultPingResponse.serializer()).takeIf { it.ok }?.name ?: throw VaultException.BadResponse() }

    /** `GET api/docs`: ids of the documents already in the vault. */
    suspend fun listDocIds(): Set<String> = authorized(
        build = { token -> Request.Builder().url(endpoint.api("docs")).get().header("Accept", "application/json").bearer(token).build() },
    ) { r -> decode(r, VaultDocList.serializer()).docs.mapTo(HashSet()) { it.id } }

    /**
     * `GET api/docs/{docId}`: whether the vault already has the document. A JSON `not_found` 404
     * means "missing"; any other 404 (a plain page) still means the URL is not a vault.
     */
    suspend fun hasDoc(docId: String): Boolean = authorized(
        build = { token -> Request.Builder().url(endpoint.api("docs", docId)).get().header("Accept", "application/json").bearer(token).build() },
        missingIsAnswer = true,
    ) { r -> r.code != 404 }

    /**
     * `GET api/docs/{docId}`: the note text the vault stores for the document, or null when the
     * vault does not have it (or did not say).
     */
    suspend fun storedMarkdown(docId: String): String? = authorized(
        build = { token -> Request.Builder().url(endpoint.api("docs", docId)).get().header("Accept", "application/json").bearer(token).build() },
        missingIsAnswer = true,
    ) { r -> if (r.code == 404) null else decode(r, VaultStoredDoc.serializer()).markdown }

    /** `PUT api/docs/{docId}` with a [VaultDocUpsert]. */
    suspend fun putDoc(docId: String, doc: VaultDocUpsert) {
        val json = VaultJson.encodeToString(VaultDocUpsert.serializer(), doc)
        putJson(endpoint.api("docs", docId), json, what = "The note text")
    }

    /** `PUT api/docs/{docId}/transcripts/{recordingId}` with a [VaultTranscriptUpsert]. */
    suspend fun putTranscript(docId: String, recordingId: String, transcript: VaultTranscriptUpsert) {
        val json = VaultJson.encodeToString(VaultTranscriptUpsert.serializer(), transcript)
        putJson(endpoint.api("docs", docId, "transcripts", recordingId), json, what = "The transcript")
    }

    /** `PUT api/docs/{docId}/files/{name}` streaming [file] with a Content-Length. */
    suspend fun putFile(docId: String, name: String, file: File, contentType: String) {
        if (file.length() > VaultNames.MAX_FILE_BYTES) throw VaultException.TooLarge(name)
        putBody(docId, name, file.asRequestBody(contentType.toMediaType()))
    }

    suspend fun putBytes(docId: String, name: String, bytes: ByteArray, contentType: String) {
        if (bytes.size > VaultNames.MAX_FILE_BYTES) throw VaultException.TooLarge(name)
        putBody(docId, name, bytes.toRequestBody(contentType.toMediaType()))
    }

    private suspend fun putBody(docId: String, name: String, body: RequestBody) {
        val url = endpoint.api("docs", docId, "files", name)
        authorized(build = { token -> Request.Builder().url(url).put(body).bearer(token).build() }, what = name) { }
    }

    private suspend fun putJson(url: okhttp3.HttpUrl, json: String, what: String) {
        val body = json.toRequestBody(JSON)
        authorized(build = { token -> Request.Builder().url(url).put(body).bearer(token).build() }, what = what) { }
    }

    /** Runs [build] with a valid token; a 401 clears the cached session and logs in once more. */
    private suspend fun <T> authorized(
        build: (String) -> Request,
        what: String? = null,
        missingIsAnswer: Boolean = false,
        handle: (Response) -> T,
    ): T {
        var session = cachedOrLogin()
        var response = execute(build(session.token))
        if (response.code == 401) {
            response.close()
            session = loginLock.withLock {
                // Another request may already have refreshed the token.
                val current = credentials.session()
                if (current != null && current.token != session.token && current.isUsable(clock())) current else freshLogin()
            }
            response = execute(build(session.token))
            if (response.code == 401) {
                response.close()
                credentials.saveSession(null)
                throw VaultException.Unauthorized()
            }
        }
        return response.use { r ->
            when {
                r.isSuccessful -> handle(r)
                missingIsAnswer && r.code == 404 && isJsonNotFound(r) -> handle(r)
                else -> throw errorFor(r, what)
            }
        }
    }

    private fun isJsonNotFound(r: Response): Boolean {
        val body = runCatching { r.peekBody(4096).string() }.getOrDefault("")
        return runCatching { VaultJson.decodeFromString(VaultErrorBody.serializer(), body) }.getOrNull()?.error == "not_found"
    }

    private suspend fun cachedOrLogin(): VaultSession {
        credentials.session()?.takeIf { it.isUsable(clock()) }?.let { return it }
        return loginLock.withLock {
            credentials.session()?.takeIf { it.isUsable(clock()) } ?: freshLogin()
        }
    }

    /** Caller holds [loginLock]. */
    private suspend fun freshLogin(): VaultSession {
        credentials.saveSession(null)
        val password = credentials.password()?.takeIf { it.isNotEmpty() } ?: throw VaultException.MissingPassword()
        val session = login(password)
        credentials.saveSession(session)
        return session
    }

    private fun <T> decode(r: Response, serializer: KSerializer<T>): T {
        val text = try {
            r.body?.string().orEmpty()
        } catch (e: IOException) {
            throw VaultException.fromIo(e)
        }
        return try {
            VaultJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            throw VaultException.BadResponse()
        }
    }

    private fun errorFor(r: Response, what: String?): VaultException {
        val body = runCatching { r.peekBody(4096).string() }.getOrDefault("")
        val err = runCatching { VaultJson.decodeFromString(VaultErrorBody.serializer(), body) }.getOrNull()
        return when (r.code) {
            401 -> if (err?.error == "invalid_password") VaultException.WrongPassword() else VaultException.Unauthorized()
            404 -> VaultException.NotAVault()
            408 -> VaultException.Server(408)
            413 -> VaultException.TooLarge(what ?: "The upload")
            429 -> VaultException.Locked(
                err?.retryAfterSeconds ?: r.header("Retry-After")?.trim()?.toLongOrNull() ?: DEFAULT_LOCK_SECONDS,
            )
            503 -> if (err?.error == "not_configured") VaultException.NotConfigured() else VaultException.Server(503)
            in 500..599 -> VaultException.Server(r.code)
            else -> VaultException.Rejected(r.code, err?.detail ?: err?.error)
        }
    }

    /**
     * Every URL derives from [endpoint], which [VaultUrl.parse] only creates for https. The vault
     * itself never redirects; a site in front of it may move between `www.host` and `host`, and
     * only that is followed (once) and adopted. Any other redirect is an error.
     */
    private suspend fun execute(request: Request): Response {
        val response = send(request)
        if (response.code !in 300..399) return response
        val code = response.code
        val location = response.header("Location")
        response.close()
        val host = VaultRedirect.movedHost(request.url, code, location)
            ?: throw VaultException.Redirected(code, VaultRedirect.targetHost(request.url, location))
        val retried = send(request.newBuilder().url(request.url.newBuilder().host(host).build()).build())
        if (retried.code in 300..399) {
            val again = retried.header("Location")
            retried.close()
            throw VaultException.Redirected(retried.code, VaultRedirect.targetHost(request.url, again))
        }
        // A plain 404 means nothing is there: keep the link as it is.
        if (retried.code != 404 || isJsonNotFound(retried)) adopt(host)
        return retried
    }

    private fun adopt(host: String) {
        val (from, to) = synchronized(this) {
            val from = current
            if (from.base.host == host) return
            (from to from.withHost(host)).also { current = it.second }
        }
        runCatching { onMoved(from, to) }
    }

    private suspend fun send(request: Request): Response {
        return try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw VaultException.fromIo(e)
        }
    }

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_LOCK_SECONDS = 15 * 60L
    }
}
