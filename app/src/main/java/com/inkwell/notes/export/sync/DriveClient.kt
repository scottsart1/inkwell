package com.inkwell.notes.export.sync

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Hands out OAuth access tokens for Drive (Google Play services in the app, a fixed token in tests). */
fun interface DriveTokens {
    /**
     * A token for the drive.file scope. [refresh] = true when Drive refused the previous one, so a
     * cached token must not be handed out again. Throws [DriveException] (not connected, needs
     * sign-in, not set up, offline).
     */
    suspend fun accessToken(refresh: Boolean): String
}

/**
 * Google Drive v3 over HTTP. Only touches files Inkwell created (drive.file scope). Uploads use
 * resumable sessions, so large recordings stream from disk. Never logs tokens or content.
 */
class DriveClient(
    http: OkHttpClient,
    private val tokens: DriveTokens,
    /** https://www.googleapis.com/ in the app; a local server in tests. */
    baseUrl: String = DEFAULT_BASE,
) : DriveApi {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val client: OkHttpClient = http
    /** Uploads may take long on a slow connection: no overall call deadline (read/write timeouts still apply). */
    private val uploadClient: OkHttpClient = http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()

    override suspend fun accountEmail(): String? = authorized(
        build = { token -> get(api("about").addQueryParameter("fields", "user(emailAddress)").build(), token) },
    ) { r -> decode(r, AboutResponse.serializer()).user?.emailAddress?.takeIf { it.isNotBlank() } }

    override suspend fun get(id: String): DriveItem? = try {
        authorized(
            build = { token -> get(api("files", id).addQueryParameter("fields", ITEM_FIELDS_TRASHED).build(), token) },
        ) { r -> decode(r, FileResource.serializer()).takeIf { it.trashed != true }?.toItem() }
    } catch (e: DriveException.NotFound) {
        null
    }

    override suspend fun findByProperty(key: String, value: String, parentId: String?): List<DriveItem> {
        val q = buildString {
            append("appProperties has { key='").append(escape(key)).append("' and value='").append(escape(value)).append("' }")
            if (parentId != null) append(" and '").append(escape(parentId)).append("' in parents")
            append(" and trashed = false")
        }
        return list(q)
    }

    override suspend fun findFolder(parentId: String, name: String): DriveItem? = list(
        "'${escape(parentId)}' in parents and name = '${escape(name)}' and mimeType = '${DriveNames.MIME_FOLDER}' and trashed = false",
    ).firstOrNull()

    override suspend fun children(parentId: String): List<DriveItem> = list("'${escape(parentId)}' in parents and trashed = false")

    override suspend fun createFolder(name: String, parentId: String?, properties: Map<String, String>): DriveItem {
        val body = metadata(name = name, mimeType = DriveNames.MIME_FOLDER, parentId = parentId, properties = properties)
        return authorized(
            build = { token ->
                Request.Builder().url(api("files").addQueryParameter("fields", ITEM_FIELDS).build())
                    .post(body.toRequestBody(JSON)).bearer(token).build()
            },
        ) { r -> decode(r, FileResource.serializer()).toItem() }
    }

    override suspend fun update(id: String, name: String, addParent: String?, removeParents: List<String>): DriveItem {
        val url = api("files", id).addQueryParameter("fields", ITEM_FIELDS).apply {
            if (addParent != null) addQueryParameter("addParents", addParent)
            if (removeParents.isNotEmpty()) addQueryParameter("removeParents", removeParents.joinToString(","))
        }.build()
        val body = metadata(name = name)
        return authorized(
            build = { token -> Request.Builder().url(url).patch(body.toRequestBody(JSON)).bearer(token).build() },
        ) { r -> decode(r, FileResource.serializer()).toItem() }
    }

    override suspend fun trash(id: String) {
        val body = """{"trashed":true}""".toRequestBody(JSON)
        try {
            authorized(
                build = { token -> Request.Builder().url(api("files", id).addQueryParameter("fields", "id").build()).patch(body).bearer(token).build() },
            ) { }
        } catch (e: DriveException.NotFound) {
            // Already gone.
        }
    }

    override suspend fun shareWithAnyone(id: String) {
        // View only, for whoever has the link; never listed in anyone's search.
        val body = """{"role":"reader","type":"anyone","allowFileDiscovery":false}""".toRequestBody(JSON)
        authorized(
            build = { token ->
                Request.Builder().url(api("files", id, "permissions").addQueryParameter("fields", "id").build())
                    .post(body).bearer(token).build()
            },
        ) { }
    }

    override suspend fun stopSharing(id: String) {
        try {
            authorized(
                build = { token -> Request.Builder().url(api("files", id, "permissions", ANYONE_WITH_LINK).build()).delete().bearer(token).build() },
            ) { }
        } catch (e: DriveException.NotFound) {
            // Not shared (or the folder is gone): nothing to withdraw.
        }
    }

    override suspend fun upload(upload: DriveUpload): DriveItem {
        // A refused token can surface at either step; start the whole session again once.
        return try {
            uploadOnce(upload, refresh = false)
        } catch (e: TokenRefused) {
            try {
                uploadOnce(upload, refresh = true)
            } catch (again: TokenRefused) {
                throw DriveException.Unauthorized()
            }
        }
    }

    private suspend fun uploadOnce(upload: DriveUpload, refresh: Boolean): DriveItem {
        val token = tokens.accessToken(refresh)
        val existing = upload.existingId
        val start = (if (existing == null) uploadApi("files") else uploadApi("files", existing))
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", ITEM_FIELDS)
            .build()
        val meta = if (existing == null) {
            metadata(name = upload.name, mimeType = upload.convertTo, parentId = upload.parentId, properties = upload.properties)
        } else {
            metadata(name = upload.name, properties = upload.properties)
        }.toRequestBody(JSON)
        val init = Request.Builder().url(start)
            .method(if (existing == null) "POST" else "PATCH", meta)
            .header("X-Upload-Content-Type", upload.mimeType)
            .header("X-Upload-Content-Length", upload.content.length.toString())
            .bearer(token)
            .build()
        val session = send(client, init).use { r ->
            if (r.code == 401) throw TokenRefused()
            if (!r.isSuccessful) throw errorFor(r)
            r.header("Location")?.takeIf { it.isNotBlank() } ?: throw DriveException.BadResponse()
        }
        val sessionUrl = runCatching { base.resolve(session) }.getOrNull() ?: throw DriveException.BadResponse()
        val type = upload.mimeType.toMediaType()
        val media: RequestBody = when (val c = upload.content) {
            is DriveContent.Bytes -> c.bytes.toRequestBody(type)
            is DriveContent.FromFile -> c.file.asRequestBody(type)
        }
        val put = Request.Builder().url(sessionUrl).put(media).bearer(token).build()
        return send(uploadClient, put).use { r ->
            if (r.code == 401) throw TokenRefused()
            if (!r.isSuccessful) throw errorFor(r)
            decode(r, FileResource.serializer()).toItem()
        }
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun list(q: String): List<DriveItem> {
        val out = ArrayList<DriveItem>()
        var pageToken: String? = null
        var pages = 0
        do {
            val url = api("files")
                .addQueryParameter("q", q)
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken,files($ITEM_FIELDS)")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = authorized(build = { token -> get(url, token) }) { r -> decode(r, FileList.serializer()) }
            page.files.mapTo(out) { it.toItem() }
            pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null && ++pages < MAX_PAGES)
        return out
    }

    /** Sends a request with a token; on 401 gets a fresh token and tries exactly once more. */
    private suspend fun <T> authorized(build: (String) -> Request, parse: (Response) -> T): T {
        var token = tokens.accessToken(false)
        var refreshed = false
        while (true) {
            val response = send(client, build(token))
            response.use { r ->
                if (r.code == 401) {
                    if (refreshed) throw DriveException.Unauthorized()
                } else {
                    if (!r.isSuccessful) throw errorFor(r)
                    return parse(r)
                }
            }
            token = tokens.accessToken(true)
            refreshed = true
        }
    }

    private suspend fun send(client: OkHttpClient, request: Request): Response = try {
        client.newCall(request).await()
    } catch (e: IOException) {
        throw DriveException.fromIo(e)
    }

    private fun get(url: HttpUrl, token: String): Request = Request.Builder().url(url).get().bearer(token).build()

    private fun api(vararg segments: String): HttpUrl.Builder =
        base.newBuilder().addPathSegments("drive/v3").apply { segments.forEach { addPathSegment(it) } }

    private fun uploadApi(vararg segments: String): HttpUrl.Builder =
        base.newBuilder().addPathSegments("upload/drive/v3").apply { segments.forEach { addPathSegment(it) } }

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    private fun <T> decode(r: Response, serializer: kotlinx.serialization.KSerializer<T>): T {
        val text = try {
            r.body?.string().orEmpty()
        } catch (e: IOException) {
            throw DriveException.fromIo(e)
        }
        return try {
            DriveJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            throw DriveException.BadResponse()
        }
    }

    private fun errorFor(r: Response): DriveException {
        val body = runCatching { r.peekBody(8192).string() }.getOrDefault("")
        val error = runCatching { DriveJson.decodeFromString(ErrorEnvelope.serializer(), body).error }.getOrNull()
        val reasons = error?.errors.orEmpty().mapNotNull { it.reason } + listOfNotNull(error?.status)
        return classify(r.code, reasons, error?.message)
    }

    private class TokenRefused : Exception()

    companion object {
        const val DEFAULT_BASE = "https://www.googleapis.com/"
        /** Drive's fixed id of the "anyone with the link" permission. */
        const val ANYONE_WITH_LINK = "anyoneWithLink"
        /** A folder on the web, as opened from another computer. */
        fun folderUrl(id: String) = "https://drive.google.com/drive/folders/$id"
        private const val MAX_PAGES = 50
        private const val ITEM_FIELDS = "id,name,mimeType,parents,appProperties"
        private const val ITEM_FIELDS_TRASHED = "$ITEM_FIELDS,trashed"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Escapes a value for a single-quoted string in a Drive query. */
        fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

        /** Maps an HTTP error and Google's error reasons to what the user can do about it. */
        fun classify(code: Int, reasons: List<String>, message: String?): DriveException {
            val r = reasons.map { it.lowercase() }
            fun has(vararg any: String) = any.any { it.lowercase() in r }
            return when {
                code == 401 -> DriveException.Unauthorized()
                has("storageQuotaExceeded", "teamDriveFileLimitExceeded") -> DriveException.Full()
                has("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded", "sharingRateLimitExceeded", "RESOURCE_EXHAUSTED") || code == 429 ->
                    DriveException.RateLimited()
                has("accessNotConfigured", "SERVICE_DISABLED") || (message?.contains("has not been used in project") == true) ->
                    DriveException.NotSetUp(
                        "The Google Drive API is switched off in your Google Cloud project. Turn it on (APIs & Services › Library › Google Drive API) and try again.",
                    )
                has("insufficientPermissions", "insufficientScopes", "ACCESS_TOKEN_SCOPE_INSUFFICIENT", "authError") -> DriveException.NeedsSignIn()
                code == 404 || has("notFound") -> DriveException.NotFound()
                code == 408 -> DriveException.Server(408)
                code in 500..599 -> DriveException.Server(code)
                else -> DriveException.Rejected(code, reasons.firstOrNull())
            }
        }

        /** Request body of a file resource (name, type, parent, app properties); only set fields are sent. */
        internal fun metadata(
            name: String,
            mimeType: String? = null,
            parentId: String? = null,
            properties: Map<String, String> = emptyMap(),
        ): String = buildJsonObject {
            put("name", name)
            if (mimeType != null) put("mimeType", mimeType)
            if (parentId != null) put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
            if (properties.isNotEmpty()) put("appProperties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
        }.toString()
    }
}

internal val DriveJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
internal data class FileResource(
    val id: String = "",
    val name: String = "",
    val mimeType: String = "",
    val parents: List<String> = emptyList(),
    val appProperties: Map<String, String> = emptyMap(),
    val trashed: Boolean? = null,
) {
    fun toItem(): DriveItem {
        if (id.isEmpty()) throw DriveException.BadResponse()
        return DriveItem(id, name, mimeType, parents, appProperties)
    }
}

@Serializable
internal data class FileList(val files: List<FileResource> = emptyList(), val nextPageToken: String? = null)

@Serializable
internal data class AboutUser(val emailAddress: String? = null)

@Serializable
internal data class AboutResponse(val user: AboutUser? = null)

@Serializable
internal data class ErrorReason(val reason: String? = null, val domain: String? = null)

@Serializable
internal data class ErrorBody(val code: Int = 0, val message: String? = null, val status: String? = null, val errors: List<ErrorReason> = emptyList())

@Serializable
internal data class ErrorEnvelope(val error: ErrorBody? = null)

/** Suspends on an OkHttp call; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // Cancelled between here and the caller resuming: nobody else will close it.
            if (cont.isActive) cont.resume(response) { _, value, _ -> value.close() } else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
