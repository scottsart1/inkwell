package com.inkwell.notes.export.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire types of docs/VAULT_API.md. Field names are the contract; do not rename. */

@Serializable
data class VaultDocUpsert(
    val title: String,
    /** "NOTEBOOK" | "PDF" | "WHITEBOARD" | "TYPED". */
    val kind: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val folderPath: String? = null,
    val pageCount: Int? = null,
    val tags: List<String>? = null,
    /** Omitted for metadata-only upserts (the vault then stores an empty body). */
    val markdown: String? = null,
)

@Serializable
data class VaultSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null,
)

@Serializable
data class VaultTranscriptUpsert(
    val title: String,
    val startedAt: Long,
    val durationMs: Long,
    val source: String,
    val segments: List<VaultSegment>,
)

@Serializable
internal data class VaultLoginRequest(val password: String)

@Serializable
internal data class VaultLoginResponse(val token: String = "", val expiresAt: Long = 0)

@Serializable
internal data class VaultPingResponse(val ok: Boolean = false, val name: String? = null)

@Serializable
internal data class VaultDocSummary(val id: String = "")

/** The part of `GET api/docs/{docId}` a metadata update keeps: the stored note text. */
@Serializable
internal data class VaultStoredDoc(val markdown: String? = null)

@Serializable
internal data class VaultDocList(val docs: List<VaultDocSummary> = emptyList())

@Serializable
internal data class VaultErrorBody(
    val error: String? = null,
    val detail: String? = null,
    val retryAfterSeconds: Long? = null,
)

/** A bearer token and its expiry (epoch ms) as returned by `POST api/session`. */
data class VaultSession(val token: String, val expiresAt: Long) {
    fun isUsable(now: Long, marginMs: Long = 60_000): Boolean = token.isNotEmpty() && expiresAt - marginMs > now
    override fun toString(): String = "VaultSession(expiresAt=$expiresAt)"
}

internal val VaultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = false
}

/** Names the app uses inside the vault (docs/VAULT_API.md, "Files the app uploads by convention"). */
object VaultNames {
    const val NOTES_PDF = "notes.pdf"
    const val NOTES_MD = "notes.md"
    const val MIME_PDF = "application/pdf"
    const val MIME_MARKDOWN = "text/markdown; charset=utf-8"
    const val MIME_AUDIO = "audio/mp4"
    const val MAX_FILE_BYTES = 95L * 1024 * 1024
    const val MAX_MARKDOWN_BYTES = 2 * 1024 * 1024

    private val DOC_ID = Regex("^[A-Za-z0-9-]{1,64}$")
    private val RECORDING_ID = Regex("^[A-Za-z0-9._-]{1,100}$")

    /** The vault id for a local document id: the id itself when it fits `[A-Za-z0-9-]{1,64}`. */
    fun docId(localId: String): String =
        if (DOC_ID.matches(localId)) localId else "x-" + sha256Hex(localId).take(48)

    /** The vault id for a local recording id: the id itself when it fits `[A-Za-z0-9._-]{1,100}`. */
    fun recordingId(localId: String): String =
        if (RECORDING_ID.matches(localId) && !localId.all { it == '.' }) localId else "r-" + sha256Hex(localId).take(48)

    fun audioFile(recordingId: String): String = "rec_${recordingId(recordingId)}.m4a"

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
