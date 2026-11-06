package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.NotesExportFormat
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.TranscriptSegment
import java.io.File

/** What a queue item copies. Stored by name in `sync_queue.kind`. */
enum class SyncKind { NOTES, TRANSCRIPT, AUDIO }

/** Which queue items one run handles (each background run has its own network constraint). */
enum class SyncScope {
    /** Everything that can go now ("Sync now" and the connected background run). */
    ALL,
    /** Folder copies only: needs no network. */
    FOLDER,
    /** Audio for Google Drive only: waits for an unmetered network. */
    DRIVE_AUDIO;

    fun accepts(item: com.inkwell.notes.core.storage.db.SyncQueueEntity): Boolean = when (this) {
        ALL -> true
        FOLDER -> item.target == OutputTarget.FOLDER.name
        DRIVE_AUDIO -> item.target == OutputTarget.DRIVE.name && item.kind == SyncKind.AUDIO.name
    }
}

/** Row status values of `sync_queue.status`. */
object SyncItemStatus {
    const val PENDING = "PENDING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

fun DestinationPrefs.targetsFor(kind: SyncKind): Set<OutputTarget> = when (kind) {
    SyncKind.NOTES -> notes
    SyncKind.TRANSCRIPT -> transcripts
    SyncKind.AUDIO -> audio
}

fun DestinationPrefs.withTargets(kind: SyncKind, targets: Set<OutputTarget>): DestinationPrefs = when (kind) {
    SyncKind.NOTES -> copy(notes = targets)
    SyncKind.TRANSCRIPT -> copy(transcripts = targets)
    SyncKind.AUDIO -> copy(audio = targets)
}

val DestinationPrefs.hasAnyTarget: Boolean get() = notes.isNotEmpty() || transcripts.isNotEmpty() || audio.isNotEmpty()

/** Removes the folder destination everywhere (used when its permission was revoked). */
fun DestinationPrefs.withoutFolder(): DestinationPrefs = copy(
    notes = notes - OutputTarget.FOLDER,
    transcripts = transcripts - OutputTarget.FOLDER,
    audio = audio - OutputTarget.FOLDER,
    folderUri = null,
    folderLabel = null,
)

/** Removes Google Drive everywhere (disconnected). */
fun DestinationPrefs.withoutDrive(): DestinationPrefs = copy(
    notes = notes - OutputTarget.DRIVE,
    transcripts = transcripts - OutputTarget.DRIVE,
    audio = audio - OutputTarget.DRIVE,
    driveAccount = null,
)

/** True when any output type goes to [target]. */
fun DestinationPrefs.uses(target: OutputTarget): Boolean = target in notes || target in transcripts || target in audio

val NotesExportFormat.includesPdf: Boolean get() = this != NotesExportFormat.MARKDOWN
val NotesExportFormat.includesMarkdown: Boolean get() = this != NotesExportFormat.PDF

data class StoredTranscript(val segments: List<TranscriptSegment>, val source: String)

/** Everything sync reads from the rest of the app. Implemented over [com.inkwell.notes.app.AppGraph]. */
interface SyncSource {
    suspend fun document(docId: String): DocumentMeta?
    suspend fun allDocuments(): List<DocumentMeta>
    /** "Economics / Term 1" (empty at the library root). */
    suspend fun folderPath(folderId: String?): String
    /** Folder names from the outermost folder down to [folderId]. */
    suspend fun folderNames(folderId: String?): List<String>
    suspend fun markdown(docId: String): String
    suspend fun exportPdf(docId: String): File
    suspend fun exportAudio(recordingId: String): File
    /** True when [file] is a temporary export that sync may delete after copying it. */
    fun isTemporary(file: File): Boolean
    suspend fun recording(recordingId: String): Recording?
    suspend fun recordings(docId: String): List<Recording>
    suspend fun transcript(recordingId: String): StoredTranscript?
    suspend fun markSynced(docId: String, at: Long)
}

/** Small persistent state of the sync engine. */
interface SyncStateStore {
    var lastSuccessAt: Long
    var lastError: String?
    /** Id of the "Inkwell" folder in Google Drive (null until created or after disconnecting). */
    var driveRootId: String?
    /** Where the document's files were last written inside the folder destination. */
    fun folderLocation(docId: String): List<String>?
    fun setFolderLocation(docId: String, segments: List<String>?)
    /** Id of the document's own folder in Google Drive. */
    fun driveDocFolder(docId: String): String?
    fun setDriveDocFolder(docId: String, folderId: String?)
    /** Forgets every Drive id (another account, or Drive disconnected). */
    fun clearDrive()
}

interface NetworkState {
    fun isOnline(): Boolean
    fun isUnmetered(): Boolean
}

class InMemorySyncState : SyncStateStore {
    override var lastSuccessAt: Long = 0
    override var lastError: String? = null
    override var driveRootId: String? = null
    private val locations = HashMap<String, List<String>>()
    private val driveFolders = HashMap<String, String>()
    override fun folderLocation(docId: String): List<String>? = synchronized(locations) { locations[docId] }
    override fun setFolderLocation(docId: String, segments: List<String>?) {
        synchronized(locations) { if (segments == null) locations.remove(docId) else locations[docId] = segments }
    }
    override fun driveDocFolder(docId: String): String? = synchronized(driveFolders) { driveFolders[docId] }
    override fun setDriveDocFolder(docId: String, folderId: String?) {
        synchronized(driveFolders) { if (folderId == null) driveFolders.remove(docId) else driveFolders[docId] = folderId }
    }
    override fun clearDrive() {
        driveRootId = null
        synchronized(driveFolders) { driveFolders.clear() }
    }
}
