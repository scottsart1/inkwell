package com.inkwell.notes.export.sync

import java.io.File

/** A file or folder in Google Drive as Inkwell sees it (drive.file: only what Inkwell created). */
data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    /** Private app properties ("appProperties"): how Inkwell recognises its own files. */
    val properties: Map<String, String> = emptyMap(),
) {
    val isFolder: Boolean get() = mimeType == DriveNames.MIME_FOLDER
}

/** What an upload sends: small text in memory, or a file streamed from disk. */
sealed class DriveContent {
    abstract val length: Long

    class Bytes(val bytes: ByteArray) : DriveContent() {
        override val length: Long get() = bytes.size.toLong()
    }

    class FromFile(val file: File) : DriveContent() {
        override val length: Long get() = file.length()
    }
}

data class DriveUpload(
    /** Replaces this file's content (and name) when set; creates a new file otherwise. */
    val existingId: String?,
    val parentId: String,
    val name: String,
    /** Media type of [content]. */
    val mimeType: String,
    /** Google Workspace type to convert into (e.g. a Google Doc); null keeps the file as it is. */
    val convertTo: String? = null,
    val properties: Map<String, String> = emptyMap(),
    val content: DriveContent,
)

/**
 * The few Google Drive v3 operations sync needs. [DriveClient] implements it over HTTP; tests use
 * an in-memory Drive. Every call throws [DriveException] on failure.
 */
interface DriveApi {
    /** The email of the connected account (for "Connected as ..."). */
    suspend fun accountEmail(): String?

    /** The item with [id] when it exists and is not in the trash; null otherwise. */
    suspend fun get(id: String): DriveItem?

    /** Items (not trashed) whose app property [key] is [value], optionally only inside [parentId]. */
    suspend fun findByProperty(key: String, value: String, parentId: String? = null): List<DriveItem>

    /** A folder (not trashed) named [name] directly inside [parentId]. */
    suspend fun findFolder(parentId: String, name: String): DriveItem?

    /** Everything (not trashed) directly inside [parentId]. */
    suspend fun children(parentId: String): List<DriveItem>

    /** Creates a folder; [parentId] null puts it at the top of My Drive. */
    suspend fun createFolder(name: String, parentId: String?, properties: Map<String, String> = emptyMap()): DriveItem

    /** Renames [id] and/or moves it into [addParent], out of [removeParents]. */
    suspend fun update(id: String, name: String, addParent: String? = null, removeParents: List<String> = emptyList()): DriveItem

    /** Creates or replaces a file. */
    suspend fun upload(upload: DriveUpload): DriveItem

    /** Moves one of Inkwell's own files to the Drive trash (recoverable there for 30 days). */
    suspend fun trash(id: String)
}

/** Names and app properties Inkwell uses inside Google Drive. */
object DriveNames {
    const val ROOT_FOLDER = FolderNaming.ROOT
    const val MIME_FOLDER = "application/vnd.google-apps.folder"
    const val MIME_GOOGLE_DOC = "application/vnd.google-apps.document"
    const val MIME_HTML = "text/html"

    /** On the "Inkwell" folder itself. */
    const val PROP_ROOT = "inkwellRoot"
    /** On each document's folder: the local document id. */
    const val PROP_DOC = "inkwellDoc"
    /** On each file Inkwell writes: which output it is ([NOTES_PDF_KEY], [transcriptKey], [audioKey]). */
    const val PROP_FILE = "inkwellFile"

    const val NOTES_PDF_KEY = "notes.pdf"
    const val NOTES_MD_KEY = "notes.md"

    fun transcriptKey(recordingId: String) = propertyValue("transcript:$recordingId")
    fun audioKey(recordingId: String) = propertyValue("audio:$recordingId")
    fun docKey(documentId: String) = propertyValue(documentId)

    /** Google Doc names carry no extension: "transcript_Lecture 3". */
    fun transcriptDocName(stem: String) = FolderNaming.transcriptFile(stem).removeSuffix(".md")

    /**
     * Drive limits an app property to 124 UTF-8 bytes, key and value together. Ids are UUIDs and
     * fit; anything longer is replaced by a stable hash so it still identifies the same thing.
     */
    fun propertyValue(raw: String): String {
        if (PROP_FILE.length + raw.encodeToByteArray().size <= MAX_PROPERTY_BYTES) return raw
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return "h:" + hash.take(48)
    }

    private const val MAX_PROPERTY_BYTES = 124
}
