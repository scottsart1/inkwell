package com.inkwell.notes.export.sync

import android.util.Log
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.core.storage.db.SyncQueueDao
import com.inkwell.notes.core.storage.db.SyncQueueEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Outcome of one pass over the queue. */
data class SyncRunReport(
    val succeeded: Int = 0,
    val failed: Int = 0,
    /** Items left waiting for a condition (network, unmetered network, Drive connection). */
    val deferred: Int = 0,
    /** A retryable failure happened: run again later with backoff. */
    val retryLater: Boolean = false,
    /** Google Drive asked Inkwell to slow down: wait a few minutes before the next run. */
    val slowDown: Boolean = false,
    /** Most relevant error of this run, user-readable; null when everything went through. */
    val error: String? = null,
)

/**
 * Processes `sync_queue`: copies notes (PDF/Markdown), transcripts and audio to the folder and/or
 * Google Drive destinations. Local data is never changed except DocumentMeta.lastSyncedAt;
 * deleting a document never deletes its copies. One run at a time per process.
 */
class SyncEngine(
    private val queue: () -> SyncQueueDao,
    private val source: SyncSource,
    private val prefs: () -> DestinationPrefs,
    /** A Drive client for the connected account (the email in [DestinationPrefs.driveAccount]). */
    private val driveClient: (account: String) -> DriveApi,
    private val folders: FolderAccess,
    private val network: NetworkState,
    private val state: SyncStateStore,
    /** Called when the folder permission is gone: clear the folder destination in settings. */
    private val onFolderRevoked: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    private val runLock = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile private var dirty = false

    // ------------------------------------------------------------------ enqueueing

    /** Adds one item per target, replacing open items for the same thing. Returns how many. */
    suspend fun enqueue(docId: String, kind: SyncKind, targets: Set<OutputTarget>, refId: String? = null): Int {
        if (targets.isEmpty()) return 0
        val now = clock()
        for (target in targets.sortedBy { it.ordinal }) {
            queue().enqueueUnique(
                SyncQueueEntity(
                    documentId = docId, kind = kind.name, target = target.name, refId = refId,
                    enqueuedAt = now, attempts = 0, lastError = null, status = SyncItemStatus.PENDING,
                ),
            )
        }
        dirty = true
        return targets.size
    }

    /** Notes of documents edited since they were last copied anywhere. */
    suspend fun enqueueChangedDocuments(prefs: DestinationPrefs = this.prefs()): Int {
        if (prefs.notes.isEmpty()) return 0
        var n = 0
        for (doc in source.allDocuments()) {
            if (doc.trashedAt != null) continue
            if (doc.lastSyncedAt == 0L || doc.modifiedAt > doc.lastSyncedAt + CHANGE_TOLERANCE_MS) {
                n += enqueue(doc.id, SyncKind.NOTES, prefs.notes)
            }
        }
        return n
    }

    /**
     * Every document, transcript and finished recording, for the chosen targets. [only] limits it to
     * one output type and/or target (used when a destination was just switched on).
     */
    suspend fun enqueueEverything(
        prefs: DestinationPrefs = this.prefs(),
        onlyKind: SyncKind? = null,
        onlyTarget: OutputTarget? = null,
    ): Int {
        fun targets(kind: SyncKind): Set<OutputTarget> {
            if (onlyKind != null && onlyKind != kind) return emptySet()
            val t = prefs.targetsFor(kind)
            return if (onlyTarget == null) t else t.intersect(setOf(onlyTarget))
        }
        val notes = targets(SyncKind.NOTES)
        val transcripts = targets(SyncKind.TRANSCRIPT)
        val audio = targets(SyncKind.AUDIO)
        if (notes.isEmpty() && transcripts.isEmpty() && audio.isEmpty()) return 0
        var n = 0
        for (doc in source.allDocuments().sortedByDescending { it.modifiedAt }) {
            if (doc.trashedAt != null) continue
            n += enqueue(doc.id, SyncKind.NOTES, notes)
            if (transcripts.isEmpty() && audio.isEmpty()) continue
            for (rec in source.recordings(doc.id)) {
                if (rec.transcriptStatus == TranscriptStatus.DONE) n += enqueue(doc.id, SyncKind.TRANSCRIPT, transcripts, rec.id)
                if (rec.isFinished()) n += enqueue(doc.id, SyncKind.AUDIO, audio, rec.id)
            }
        }
        return n
    }

    // ------------------------------------------------------------------ running

    /** Processes every item in [scope] that can go now. Waits if another run is in progress. */
    suspend fun run(scope: SyncScope = SyncScope.ALL): SyncRunReport = runLock.withLock {
        _running.value = true
        try {
            withContext(io) { runLocked(scope) }
        } finally {
            _running.value = false
        }
    }

    private suspend fun runLocked(scope: SyncScope): SyncRunReport {
        val ctx = RunContext(prefs())
        val attempted = HashSet<Long>()
        try {
            while (true) {
                dirty = false
                val items = queue().pending(QUERY_LIMIT)
                    .filter { it.rowId !in attempted && scope.accepts(it) }
                    .sortedWith(compareBy<SyncQueueEntity>({ kindOrder(it.kind) }, { it.documentId }, { it.enqueuedAt }, { it.rowId }))
                if (items.isEmpty() && !dirty) break
                for (item in items) {
                    currentCoroutineContext().ensureActive()
                    attempted += item.rowId
                    processOne(item, ctx)
                }
            }
        } finally {
            ctx.exports.clear()
        }
        val now = clock()
        runCatching {
            queue().pruneDone(now - KEEP_DONE_MS)
            queue().pruneFailed(now - KEEP_FAILED_MS)
        }
        if (ctx.succeeded > 0) state.lastSuccessAt = ctx.lastSuccessAt
        val error = ctx.error
        if (error != null) state.lastError = error
        // A partial run (folder only, audio only) can't vouch for the other destination.
        else if (scope == SyncScope.ALL && (ctx.succeeded > 0 || ctx.failed == 0)) state.lastError = null
        return SyncRunReport(
            succeeded = ctx.succeeded,
            failed = ctx.failed,
            deferred = ctx.deferred,
            retryLater = ctx.retryLater,
            slowDown = ctx.slowDown,
            error = error,
        )
    }

    private suspend fun processOne(item: SyncQueueEntity, ctx: RunContext) {
        val kind = runCatching { SyncKind.valueOf(item.kind) }.getOrNull()
        val target = runCatching { OutputTarget.valueOf(item.target) }.getOrNull()
        if (kind == null || target == null || target !in ctx.prefs.targetsFor(kind)) {
            // Unknown row or the destination was switched off meanwhile: nothing to do.
            finish(item, SyncItemStatus.DONE, item.attempts, null)
            return
        }
        when (target) {
            OutputTarget.DRIVE -> {
                if (!network.isOnline()) return ctx.defer()
                if (kind == SyncKind.AUDIO && ctx.prefs.audioOnUnmeteredOnly && !network.isUnmetered()) return ctx.defer()
                if (ctx.driveBlocked != null) return ctx.defer()
            }
            OutputTarget.FOLDER -> if (ctx.folderBlocked != null) return ctx.defer()
        }
        try {
            val outcome = when (kind) {
                SyncKind.NOTES -> syncNotes(item, target, ctx)
                SyncKind.TRANSCRIPT -> syncTranscript(item, target, ctx)
                SyncKind.AUDIO -> syncAudio(item, target, ctx)
            }
            when (outcome) {
                Outcome.Done -> {
                    finish(item, SyncItemStatus.DONE, item.attempts, null)
                    ctx.succeeded++
                    ctx.lastSuccessAt = clock()
                }
                Outcome.Skipped -> finish(item, SyncItemStatus.DONE, item.attempts, null)
                // Still recording: onRecordingFinished enqueues it again when it ends.
                Outcome.NotReady -> ctx.defer()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveException) {
            onDriveError(item, e, ctx)
        } catch (e: FolderRevokedException) {
            ctx.folderBlocked = e
            ctx.failed++
            ctx.error = e.message
            finish(item, SyncItemStatus.FAILED, item.attempts + 1, e.message)
            runCatching { queue().failPendingForTarget(OutputTarget.FOLDER.name, e.message.orEmpty()) }
            runCatching { onFolderRevoked() }
        } catch (e: FolderUnavailableException) {
            ctx.folderBlocked = e
            ctx.retryLater = true
            ctx.error = e.message
            finish(item, SyncItemStatus.PENDING, item.attempts + 1, e.message)
        } catch (e: SecurityException) {
            val revoked = FolderRevokedException(e)
            if (target == OutputTarget.FOLDER) {
                ctx.folderBlocked = revoked
                runCatching { queue().failPendingForTarget(OutputTarget.FOLDER.name, revoked.message.orEmpty()) }
                runCatching { onFolderRevoked() }
            }
            ctx.failed++
            ctx.error = if (target == OutputTarget.FOLDER) revoked.message else genericError(item, ctx, e)
            finish(item, SyncItemStatus.FAILED, item.attempts + 1, ctx.error)
        } catch (e: Throwable) {
            // IOException from the folder, export failures, anything else: retry a few times.
            val attempts = item.attempts + 1
            val message = genericError(item, ctx, e)
            Log.w(TAG, "Sync ${item.kind}->${item.target} failed: ${e.javaClass.simpleName}")
            val giveUp = attempts >= MAX_ATTEMPTS || e is NotImplementedError || e is OutOfMemoryError
            if (giveUp) ctx.failed++ else ctx.retryLater = true
            ctx.error = message
            finish(item, if (giveUp) SyncItemStatus.FAILED else SyncItemStatus.PENDING, attempts, message)
        }
    }

    private suspend fun onDriveError(item: SyncQueueEntity, e: DriveException, ctx: RunContext) {
        Log.w(TAG, "Drive ${item.kind} failed: ${e.javaClass.simpleName}")
        ctx.error = e.message
        if (e is DriveException.NotFound) {
            // A folder was deleted in Drive while Inkwell used it: find or recreate it next time.
            ctx.driveLayout?.forgetDocument(item.documentId)
            ctx.driveLayout?.reset()
        }
        if (e.blocksDrive) {
            // Account, setup or connectivity problem: keep the item, stop Drive work for this run.
            ctx.driveBlocked = e
            ctx.deferred++
            if (e.retryable) ctx.retryLater = true
            if (e is DriveException.RateLimited) ctx.slowDown = true
            val attempts = if (e is DriveException.Unreachable) item.attempts + 1 else item.attempts
            finish(item, SyncItemStatus.PENDING, attempts, e.message)
            return
        }
        val attempts = item.attempts + 1
        val giveUp = !e.retryable || attempts >= MAX_ATTEMPTS
        if (giveUp) ctx.failed++ else ctx.retryLater = true
        finish(item, if (giveUp) SyncItemStatus.FAILED else SyncItemStatus.PENDING, attempts, e.message)
    }

    private suspend fun genericError(item: SyncQueueEntity, ctx: RunContext, e: Throwable): String {
        val title = runCatching { source.document(item.documentId)?.title }.getOrNull()
        val what = when (item.kind) {
            SyncKind.TRANSCRIPT.name -> "the transcript"
            SyncKind.AUDIO.name -> "the recording"
            else -> "the notes"
        }
        val where = if (item.target == OutputTarget.FOLDER.name) "to the folder" else "to Google Drive"
        val reason = when (e) {
            is IOException -> e.message?.takeIf { it.isNotBlank() && it.length < 200 } ?: "a file couldn't be written"
            is OutOfMemoryError -> "not enough memory"
            else -> "it couldn't be exported"
        }
        val doc = title?.let { "“$it”" } ?: "a document"
        return "Couldn't copy $what of $doc $where: $reason"
    }

    private suspend fun finish(item: SyncQueueEntity, status: String, attempts: Int, error: String?) {
        queue().setOutcome(item.rowId, status, attempts, error?.take(500))
    }

    // ------------------------------------------------------------------ per kind

    private suspend fun syncNotes(item: SyncQueueEntity, target: OutputTarget, ctx: RunContext): Outcome {
        val doc = source.document(item.documentId)?.takeIf { it.trashedAt == null } ?: return Outcome.Skipped
        val format = ctx.prefs.notesFormat
        // Edits made while this copy is being made must count as unsynced: stamp the start time.
        val startedAt = clock()
        when (target) {
            OutputTarget.DRIVE -> {
                val drive = ctx.drive()
                val dir = drive.documentFolder(doc, source.folderNames(doc.folderId))
                if (format.includesPdf) {
                    drive.put(dir, DriveNames.NOTES_PDF_KEY, FolderNaming.NOTES_PDF, MIME_PDF, DriveContent.FromFile(ctx.pdf(doc.id)))
                }
                if (format.includesMarkdown) {
                    val bytes = ctx.markdown(doc.id).encodeToByteArray()
                    drive.put(dir, DriveNames.NOTES_MD_KEY, FolderNaming.NOTES_MD, MIME_MARKDOWN, DriveContent.Bytes(bytes))
                }
            }
            OutputTarget.FOLDER -> {
                val root = ctx.folderRoot()
                val place = documentDir(root, doc, ctx)
                if (format.includesPdf) {
                    val pdf = ctx.pdf(doc.id)
                    FolderWriter.writeFile(place.dir, FolderNaming.NOTES_PDF, MIME_PDF) { out -> pdf.inputStream().use { it.copyTo(out) } }
                }
                if (format.includesMarkdown) {
                    val bytes = ctx.markdown(doc.id).encodeToByteArray()
                    FolderWriter.writeFile(place.dir, FolderNaming.NOTES_MD, "text/markdown") { it.write(bytes) }
                }
                place.finishMove(doc, SyncKind.NOTES, null)
            }
        }
        source.markSynced(doc.id, maxOf(startedAt, doc.modifiedAt))
        return Outcome.Done
    }

    private suspend fun syncTranscript(item: SyncQueueEntity, target: OutputTarget, ctx: RunContext): Outcome {
        val recId = item.refId ?: return Outcome.Skipped
        val rec = source.recording(recId) ?: return Outcome.Skipped
        val doc = source.document(rec.documentId)?.takeIf { it.trashedAt == null } ?: return Outcome.Skipped
        val transcript = source.transcript(recId)?.takeIf { it.segments.isNotEmpty() } ?: return Outcome.Skipped
        when (target) {
            OutputTarget.DRIVE -> {
                val drive = ctx.drive()
                val dir = drive.documentFolder(doc, source.folderNames(doc.folderId))
                val stem = FolderNaming.recordingStem(rec, source.recordings(doc.id))
                val html = SyncPayloads.transcriptHtml(rec, transcript.segments, doc.title).encodeToByteArray()
                val key = DriveNames.transcriptKey(rec.id)
                try {
                    drive.put(dir, key, DriveNames.transcriptDocName(stem), DriveNames.MIME_HTML, DriveContent.Bytes(html), convertTo = DriveNames.MIME_GOOGLE_DOC)
                } catch (e: DriveException.Rejected) {
                    // Drive wouldn't make a Google Doc of it: keep the transcript as a Markdown file instead.
                    val md = SyncPayloads.transcriptMarkdown(rec, transcript.segments, doc.title).encodeToByteArray()
                    drive.put(dir, key, FolderNaming.transcriptFile(stem), MIME_MARKDOWN, DriveContent.Bytes(md))
                }
            }
            OutputTarget.FOLDER -> {
                val place = documentDir(ctx.folderRoot(), doc, ctx)
                val stem = FolderNaming.recordingStem(rec, source.recordings(doc.id))
                val text = SyncPayloads.transcriptMarkdown(rec, transcript.segments, doc.title)
                FolderWriter.writeFile(place.dir, FolderNaming.transcriptFile(stem), "text/markdown") { it.write(text.encodeToByteArray()) }
                place.finishMove(doc, SyncKind.TRANSCRIPT, rec.id)
            }
        }
        return Outcome.Done
    }

    private suspend fun syncAudio(item: SyncQueueEntity, target: OutputTarget, ctx: RunContext): Outcome {
        val recId = item.refId ?: return Outcome.Skipped
        val rec = source.recording(recId) ?: return Outcome.Skipped
        if (!rec.isFinished()) return Outcome.NotReady
        if (rec.segmentFiles.isEmpty()) return Outcome.Skipped
        val doc = source.document(rec.documentId)?.takeIf { it.trashedAt == null } ?: return Outcome.Skipped
        when (target) {
            OutputTarget.DRIVE -> {
                val drive = ctx.drive()
                val dir = drive.documentFolder(doc, source.folderNames(doc.folderId))
                val stem = FolderNaming.recordingStem(rec, source.recordings(doc.id))
                drive.put(dir, DriveNames.audioKey(rec.id), FolderNaming.audioFile(stem), MIME_AUDIO, DriveContent.FromFile(ctx.audio(rec.id)))
            }
            OutputTarget.FOLDER -> {
                val place = documentDir(ctx.folderRoot(), doc, ctx)
                val stem = FolderNaming.recordingStem(rec, source.recordings(doc.id))
                val file = ctx.audio(rec.id)
                FolderWriter.writeFile(place.dir, FolderNaming.audioFile(stem), MIME_AUDIO) { out -> file.inputStream().use { it.copyTo(out) } }
                place.finishMove(doc, SyncKind.AUDIO, rec.id)
            }
        }
        return Outcome.Done
    }

    // ------------------------------------------------------------------ folder placement

    /** The document's directory, following a rename or move since the last copy. */
    private suspend fun documentDir(root: FolderNode, doc: DocumentMeta, ctx: RunContext): Placement {
        ctx.placements[doc.id]?.let { return it }
        val segments = FolderNaming.documentDir(source.folderNames(doc.folderId), doc.title, doc.id)
        val previous = state.folderLocation(doc.id)
        var oldDir: FolderNode? = null
        if (previous != null && previous != segments) {
            val old = FolderWriter.findDir(root, previous)
            if (old != null) {
                val parentSegments = segments.dropLast(1)
                val parent = if (previous.dropLast(1) == parentSegments) FolderWriter.findDir(root, parentSegments) else null
                if (parent != null && FolderWriter.renameDirInPlace(old, segments.last(), parent)) {
                    state.setFolderLocation(doc.id, segments)
                    return Placement(old, null, doc.id).also { ctx.placements[doc.id] = it }
                }
                oldDir = old
            }
        }
        val dir = FolderWriter.ensureDirs(root, segments)
        state.setFolderLocation(doc.id, segments)
        return Placement(dir, oldDir, doc.id).also { ctx.placements[doc.id] = it }
    }

    /** Where a document's files go in this run; [oldDir] holds copies from before a rename/move. */
    private inner class Placement(val dir: FolderNode, private var oldDir: FolderNode?, private val docId: String) {
        /**
         * After the first file ([doneKind]/[doneRef]) landed in the new place: copy the document's
         * other outputs there too, then remove the old copies.
         */
        suspend fun finishMove(doc: DocumentMeta, doneKind: SyncKind, doneRef: String?) {
            val old = oldDir ?: return
            oldDir = null
            val p = prefs()
            val folder = setOf(OutputTarget.FOLDER)
            fun wanted(kind: SyncKind, ref: String?) = OutputTarget.FOLDER in p.targetsFor(kind) && !(kind == doneKind && ref == doneRef)
            for (rec in source.recordings(docId)) {
                if (rec.transcriptStatus == TranscriptStatus.DONE && wanted(SyncKind.TRANSCRIPT, rec.id)) enqueue(doc.id, SyncKind.TRANSCRIPT, folder, rec.id)
                if (rec.isFinished() && wanted(SyncKind.AUDIO, rec.id)) enqueue(doc.id, SyncKind.AUDIO, folder, rec.id)
            }
            if (wanted(SyncKind.NOTES, null)) enqueue(doc.id, SyncKind.NOTES, folder)
            runCatching { FolderWriter.deleteOwnFiles(old) }
        }
    }

    // ------------------------------------------------------------------ run state

    private inner class RunContext(val prefs: DestinationPrefs) {
        var succeeded = 0
        var failed = 0
        var deferred = 0
        var retryLater = false
        var lastSuccessAt = 0L
        var error: String? = null
        var driveBlocked: DriveException? = null
        /** Google asked to slow down: the next background run waits a little. */
        var slowDown = false
        var folderBlocked: Exception? = null
        val placements = HashMap<String, Placement>()
        var driveLayout: DriveLayout? = null
            private set
        private var root: FolderNode? = null
        private var markdownCache: Pair<String, String>? = null
        val exports = ExportCache(source)

        suspend fun markdown(docId: String): String {
            markdownCache?.takeIf { it.first == docId }?.let { return it.second }
            return source.markdown(docId).also { markdownCache = docId to it }
        }

        suspend fun pdf(docId: String): File = exports.get("pdf:$docId") { source.exportPdf(docId) }
        suspend fun audio(recordingId: String): File = exports.get("audio:$recordingId") { source.exportAudio(recordingId) }

        fun defer() {
            deferred++
        }

        fun drive(): DriveLayout {
            driveBlocked?.let { throw it }
            driveLayout?.let { return it }
            val account = prefs.driveAccount?.takeIf { it.isNotBlank() } ?: throw DriveException.NotConnected()
            return DriveLayout(driveClient(account), state).also { driveLayout = it }
        }

        fun folderRoot(): FolderNode {
            root?.let { return it }
            val uri = prefs.folderUri?.takeIf { it.isNotBlank() } ?: throw FolderRevokedException()
            return folders.root(uri).also { root = it }
        }
    }

    private enum class Outcome { Done, Skipped, NotReady }

    companion object {
        private const val TAG = "InkwellSync"
        const val MAX_ATTEMPTS = 8
        const val MIME_PDF = "application/pdf"
        const val MIME_MARKDOWN = "text/markdown"
        const val MIME_AUDIO = "audio/mp4"
        private const val QUERY_LIMIT = 5000
        /** A document counts as changed when edited more than this after its last copy. */
        const val CHANGE_TOLERANCE_MS = 2_000L
        private const val KEEP_DONE_MS = 7L * 24 * 3600 * 1000
        private const val KEEP_FAILED_MS = 30L * 24 * 3600 * 1000

        private fun kindOrder(kind: String): Int = when (kind) {
            SyncKind.NOTES.name -> 0
            SyncKind.TRANSCRIPT.name -> 1
            else -> 2
        }

        fun Recording.isFinished(): Boolean = status == RecordingStatus.COMPLETE || status == RecordingStatus.INTERRUPTED
    }
}

/**
 * Keeps the last few exports of a run so a document going to both destinations is exported once;
 * temporary export files are deleted when evicted or at the end of the run.
 */
internal class ExportCache(private val source: SyncSource, private val capacity: Int = 2) {
    private val entries = LinkedHashMap<String, File>()

    suspend fun get(key: String, export: suspend () -> File): File {
        entries.remove(key)?.let { entries[key] = it; return it }
        val file = export()
        entries[key] = file
        while (entries.size > capacity) {
            val eldest = entries.keys.first()
            entries.remove(eldest)?.let { discard(it) }
        }
        return file
    }

    fun clear() {
        entries.values.forEach { discard(it) }
        entries.clear()
    }

    private fun discard(file: File) {
        if (source.isTemporary(file)) runCatching { file.delete() }
    }
}
