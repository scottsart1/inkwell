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
    /** Items left waiting for a condition (network, unmetered network, Drive connection, vault settings). */
    val deferred: Int = 0,
    /** A retryable failure happened: run again later with backoff. */
    val retryLater: Boolean = false,
    /** Google Drive asked Inkwell to slow down: wait a few minutes before the next run. */
    val slowDown: Boolean = false,
    /** The vault is locked until this time (epoch ms); 0 = not locked. */
    val vaultLockedUntil: Long = 0,
    /** Most relevant error of this run, user-readable; null when everything went through. */
    val error: String? = null,
)

/**
 * Processes `sync_queue`: copies notes (PDF/Markdown), transcripts and audio to the folder, Google
 * Drive and notes vault destinations. Local data is never changed except DocumentMeta.lastSyncedAt;
 * deleting a document never deletes its copies. One run at a time per process.
 */
class SyncEngine(
    private val queue: () -> SyncQueueDao,
    private val source: SyncSource,
    private val prefs: () -> DestinationPrefs,
    /** A Drive client for the connected account (the email in [DestinationPrefs.driveAccount]). */
    private val driveClient: (account: String) -> DriveApi,
    /** A vault client for the saved vault link (its password and session come from SecretStore). */
    private val vaultClient: (VaultEndpoint) -> VaultClient,
    private val folders: FolderAccess,
    private val network: NetworkState,
    private val state: SyncStateStore,
    /**
     * Called when the permission for the folder [revokedUri] is gone: clear the folder destination
     * in settings, unless another folder was chosen meanwhile.
     */
    private val onFolderRevoked: suspend (revokedUri: String?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    /** Resolves the saved vault link (HTTPS only); replaceable for tests against a local server. */
    private val endpointFor: (String) -> VaultEndpoint? = VaultUrl::endpointOrNull,
) {
    private val runLock = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile private var dirty = false

    /**
     * When each item that just failed may be tried again (queue row id -> epoch ms), so a run
     * started early (a kick after an edit, a recording, a settings change) does not use up the
     * retry attempts of items still waiting out their backoff during a server or folder outage.
     * In memory only: after the process restarts every waiting item gets one immediate try.
     * Guarded by [runLock].
     */
    private val retryAt = HashMap<Long, Long>()

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
     * The document's title or place in the library changed: queues what moves its copies, without
     * copying its notes to Google Drive or the vault again.
     * - Folder (only when it holds copies of the document): one output is written again in the new
     *   directory, and [Placement.finishMove] moves the rest there: the notes when they go to the
     *   folder, otherwise one transcript, otherwise one recording.
     * - Google Drive: the document's Drive folder is renamed and moved (see [driveMoveTargets]);
     *   nothing is uploaded again.
     * - Vault: the title and folder path of a document the vault already has, with its current
     *   note text, or the text the vault stores when notes don't go there (see [vaultMetadataTargets]).
     */
    suspend fun enqueueMoved(docId: String, prefs: DestinationPrefs = this.prefs()): Int {
        var n = 0
        val folder = setOf(OutputTarget.FOLDER)
        if (state.folderLocation(prefs.folderUri.orEmpty(), docId) != null) {
            if (OutputTarget.FOLDER in prefs.notes) {
                n += enqueue(docId, SyncKind.NOTES, folder)
            } else if (OutputTarget.FOLDER in prefs.transcripts || OutputTarget.FOLDER in prefs.audio) {
                val recordings = source.recordings(docId)
                // Only an output that syncTranscript/syncAudio actually writes runs finishMove: a
                // finished transcript with no segments (a silent recording) is skipped there.
                val transcript = if (OutputTarget.FOLDER !in prefs.transcripts) null else recordings.firstOrNull {
                    it.transcriptStatus == TranscriptStatus.DONE && source.transcript(it.id)?.segments?.isNotEmpty() == true
                }
                val audio = recordings.firstOrNull { it.isFinished() && it.segmentFiles.isNotEmpty() }
                    ?.takeIf { OutputTarget.FOLDER in prefs.audio }
                when {
                    transcript != null -> n += enqueue(docId, SyncKind.TRANSCRIPT, folder, transcript.id)
                    audio != null -> n += enqueue(docId, SyncKind.AUDIO, folder, audio.id)
                }
            }
        }
        if (OutputTarget.DRIVE in prefs.driveMoveTargets() && state.driveDocFolder(docId) != null) {
            n += enqueue(docId, SyncKind.NOTES, setOf(OutputTarget.DRIVE), DRIVE_MOVE_REF)
        }
        if (OutputTarget.VAULT in prefs.vaultMetadataTargets()) {
            n += enqueue(docId, SyncKind.NOTES, setOf(OutputTarget.VAULT), VAULT_METADATA_REF)
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

    /**
     * Processes every item in [scope] that can go now. Waits if another run is in progress. Items
     * that failed recently wait for their backoff ([retryDelayMs]) unless [ignoreBackoff] ("Sync now").
     */
    suspend fun run(scope: SyncScope = SyncScope.ALL, ignoreBackoff: Boolean = false): SyncRunReport = runLock.withLock {
        _running.value = true
        try {
            withContext(io) { runLocked(scope, ignoreBackoff) }
        } finally {
            _running.value = false
        }
    }

    private suspend fun runLocked(scope: SyncScope, ignoreBackoff: Boolean): SyncRunReport {
        var ctx = RunContext(prefs())
        val attempted = HashSet<Long>()
        val seenPending = HashSet<Long>()
        try {
            while (true) {
                dirty = false
                var processed = false
                // Page through the whole queue: items left waiting at its head (offline, Wi-Fi
                // only, another scope) must not hide the ones behind them.
                var afterAt = Long.MIN_VALUE
                var afterRowId = Long.MIN_VALUE
                while (true) {
                    val page = queue().pendingAfter(afterAt, afterRowId, QUERY_LIMIT)
                    page.forEach { seenPending += it.rowId }
                    val items = page
                        .filter { it.rowId !in attempted && scope.accepts(it) }
                        .sortedWith(compareBy<SyncQueueEntity>({ kindOrder(it.kind) }, { it.documentId }, { it.enqueuedAt }, { it.rowId }))
                    for (item in items) {
                        currentCoroutineContext().ensureActive()
                        // Destinations can change during a long run: each item goes by the current settings.
                        ctx = ctx.following(prefs())
                        attempted += item.rowId
                        if (!ignoreBackoff && waitingOutBackoff(item)) {
                            // Its next try is due later: the run comes back (worker retry) for it.
                            ctx.defer()
                            ctx.waitingBackoff++
                            ctx.retryLater = true
                            continue
                        }
                        processed = true
                        processOne(item, ctx)
                    }
                    if (page.size < QUERY_LIMIT) break
                    afterAt = page.last().enqueuedAt
                    afterRowId = page.last().rowId
                }
                if (!processed && !dirty) break
            }
        } finally {
            ctx.exports.clear()
        }
        // Forget rows that are no longer pending (done, failed, replaced or dropped meanwhile).
        if (scope == SyncScope.ALL) retryAt.keys.retainAll(seenPending)
        val now = clock()
        runCatching {
            queue().pruneDone(now - KEEP_DONE_MS)
            queue().pruneFailed(now - KEEP_FAILED_MS)
        }
        if (ctx.succeeded > 0) state.lastSuccessAt = ctx.lastSuccessAt
        val error = ctx.error
        if (error != null) state.lastError = error
        // A partial run (folder only, audio only) can't vouch for the other destination, and a run
        // that only skipped items waiting out their backoff proves nothing about the outage.
        else if (scope == SyncScope.ALL && (ctx.succeeded > 0 || (ctx.failed == 0 && ctx.waitingBackoff == 0))) state.lastError = null
        return SyncRunReport(
            succeeded = ctx.succeeded,
            failed = ctx.failed,
            deferred = ctx.deferred,
            retryLater = ctx.retryLater,
            slowDown = ctx.slowDown,
            vaultLockedUntil = state.vaultLockedUntil.takeIf { it > now } ?: 0,
            error = error,
        )
    }

    private suspend fun processOne(item: SyncQueueEntity, ctx: RunContext) {
        val kind = runCatching { SyncKind.valueOf(item.kind) }.getOrNull()
        val target = runCatching { OutputTarget.valueOf(item.target) }.getOrNull()
        if (kind == null || target == null || target !in targetsFor(kind, item.refId, ctx.prefs)) {
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
            OutputTarget.VAULT -> {
                if (!network.isOnline()) return ctx.defer()
                if (kind == SyncKind.AUDIO && ctx.prefs.audioOnUnmeteredOnly && !network.isUnmetered()) return ctx.defer()
                if (ctx.vaultBlocked != null) return ctx.defer()
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
        } catch (e: VaultException) {
            onVaultError(item, e, ctx)
        } catch (e: FolderRevokedException) {
            onFolderLost(item, e, ctx)
        } catch (e: FolderUnavailableException) {
            ctx.folderBlocked = e
            ctx.retryLater = true
            ctx.error = e.message
            finish(item, SyncItemStatus.PENDING, item.attempts + 1, e.message)
        } catch (e: SecurityException) {
            if (target == OutputTarget.FOLDER) return onFolderLost(item, FolderRevokedException(e), ctx)
            ctx.failed++
            ctx.error = genericError(item, ctx, e)
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

    /**
     * The folder refused access: its items fail and the destination is cleared. When the user
     * chose another folder during this run, it is the old folder's access that went away: the
     * item waits for the new folder (which gets its own copies queued) and nothing else changes.
     */
    private suspend fun onFolderLost(item: SyncQueueEntity, e: FolderRevokedException, ctx: RunContext) {
        ctx.folderBlocked = e
        val revokedUri = ctx.prefs.folderUri
        if (prefs().folderUri != revokedUri) {
            ctx.defer()
            finish(item, SyncItemStatus.PENDING, item.attempts, null)
            return
        }
        ctx.failed++
        ctx.error = e.message
        finish(item, SyncItemStatus.FAILED, item.attempts + 1, e.message)
        runCatching { queue().failPendingForTarget(OutputTarget.FOLDER.name, e.message.orEmpty()) }
        runCatching { onFolderRevoked(revokedUri) }
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
            // Waiting for Drive or the network is not a failed attempt of this item: counting
            // it would make the item give up at its first real server error later.
            finish(item, SyncItemStatus.PENDING, item.attempts, e.message)
            return
        }
        val attempts = item.attempts + 1
        val giveUp = !e.retryable || attempts >= MAX_ATTEMPTS
        if (giveUp) ctx.failed++ else ctx.retryLater = true
        finish(item, if (giveUp) SyncItemStatus.FAILED else SyncItemStatus.PENDING, attempts, e.message)
    }

    private suspend fun onVaultError(item: SyncQueueEntity, e: VaultException, ctx: RunContext) {
        Log.w(TAG, "Vault ${item.kind} failed: ${e.javaClass.simpleName}")
        ctx.error = e.message
        if (e is VaultException.Locked) state.vaultLockedUntil = clock() + e.retryAfterSeconds * 1000
        // The vault counts failed logins per address and locks it after a few: once the stored
        // password was refused, stop trying until it changes (or the lockout window has passed).
        if (e is VaultException.WrongPassword && !ctx.heldForCredentials) state.vaultCredentialsRefusedAt = clock()
        if (e.blocksVault) {
            // Settings or connectivity problem: keep the item, stop vault work for this run.
            ctx.vaultBlocked = e
            ctx.deferred++
            if (e.retryable) ctx.retryLater = true
            // Waiting for the vault or the network is not a failed attempt of this item: counting
            // it would make the item give up at its first real server error later.
            finish(item, SyncItemStatus.PENDING, item.attempts, e.message)
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
        val where = when (item.target) {
            OutputTarget.FOLDER.name -> "to the folder"
            OutputTarget.VAULT.name -> "to the notes vault"
            else -> "to Google Drive"
        }
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
        // A failed try that will be retried waits out its backoff; anything else goes when it can.
        if (status == SyncItemStatus.PENDING && attempts > item.attempts) retryAt[item.rowId] = clock() + retryDelayMs(attempts)
        else retryAt.remove(item.rowId)
    }

    private fun waitingOutBackoff(item: SyncQueueEntity): Boolean {
        val due = retryAt[item.rowId] ?: return false
        // Attempts reset to 0 ("Sync now" retrying failed items): no backoff left to wait out.
        return item.attempts > 0 && clock() < due
    }

    // ------------------------------------------------------------------ per kind

    private suspend fun syncNotes(item: SyncQueueEntity, target: OutputTarget, ctx: RunContext): Outcome {
        if (item.refId == DRIVE_MOVE_REF) return syncDriveMove(item, target, ctx)
        if (item.refId == VAULT_METADATA_REF) return syncVaultMetadata(item, target, ctx)
        val doc = source.document(item.documentId)?.takeIf { it.trashedAt == null } ?: return Outcome.Skipped
        val format = ctx.prefs.notesFormat
        // Edits made while this copy is being made must count as unsynced: stamp the start time.
        val startedAt = clock()
        when (target) {
            OutputTarget.VAULT -> {
                val client = ctx.vault()
                val id = VaultNames.docId(doc.id)
                val markdown = ctx.markdown(doc.id)
                val writtenAt = clock()
                client.putDoc(id, SyncPayloads.docUpsert(doc, source.folderPath(doc.folderId), markdown))
                ctx.knownVaultDocs?.add(id)
                ctx.vaultDocWrittenAt[doc.id] = writtenAt
                if (format.includesPdf) client.putFile(id, VaultNames.NOTES_PDF, ctx.pdf(doc.id), VaultNames.MIME_PDF)
                if (format.includesMarkdown) client.putBytes(id, VaultNames.NOTES_MD, markdown.encodeToByteArray(), VaultNames.MIME_MARKDOWN)
            }
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
            OutputTarget.VAULT -> {
                val client = ctx.vault()
                val id = ensureVaultDoc(client, doc, ctx)
                client.putTranscript(id, VaultNames.recordingId(rec.id), SyncPayloads.transcriptUpsert(rec, transcript.segments, transcript.source))
            }
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
            OutputTarget.VAULT -> {
                val client = ctx.vault()
                val id = ensureVaultDoc(client, doc, ctx)
                client.putFile(id, VaultNames.audioFile(rec.id), ctx.audio(rec.id), VaultNames.MIME_AUDIO)
            }
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

    /**
     * After a move or rename: the document's Drive folder takes its new title and place. Nothing is
     * uploaded again, and a document Drive doesn't have yet is not created.
     */
    private suspend fun syncDriveMove(item: SyncQueueEntity, target: OutputTarget, ctx: RunContext): Outcome {
        if (target != OutputTarget.DRIVE) return Outcome.Skipped
        val doc = source.document(item.documentId)?.takeIf { it.trashedAt == null } ?: return Outcome.Skipped
        if (state.driveDocFolder(doc.id) == null) return Outcome.Skipped
        ctx.drive().documentFolder(doc, source.folderNames(doc.folderId))
        return Outcome.Done
    }

    /**
     * Makes sure the vault has a document row for [doc] before a transcript or audio file is
     * attached, so the vault shows the real title. Uses the full notes when notes go to the vault
     * too; otherwise metadata only. Never overwrites a document the vault already has.
     */
    private suspend fun ensureVaultDoc(client: VaultClient, doc: DocumentMeta, ctx: RunContext): String {
        val id = VaultNames.docId(doc.id)
        if (vaultHasDoc(client, id, ctx)) return id
        val markdown = if (OutputTarget.VAULT in ctx.prefs.notes) ctx.markdown(doc.id) else null
        val writtenAt = clock()
        client.putDoc(id, SyncPayloads.docUpsert(doc, source.folderPath(doc.folderId), markdown))
        ctx.knownVaultDocs?.add(id)
        ctx.vaultDocWrittenAt[doc.id] = writtenAt
        return id
    }

    /** Whether the vault already has the document [id] (its list is loaded once per run). */
    private suspend fun vaultHasDoc(client: VaultClient, id: String, ctx: RunContext): Boolean {
        val known = ctx.knownVaultDocs ?: client.listDocIds().toHashSet().also { ctx.knownVaultDocs = it }
        if (id in known) return true
        // The vault lists at most VAULT_LIST_CAP documents; beyond that, absence from the list
        // proves nothing, and re-creating the doc metadata-only would wipe its stored notes.
        if (known.size >= VAULT_LIST_CAP && client.hasDoc(id)) {
            known += id
            return true
        }
        return false
    }

    /**
     * After a move or rename: the vault gets the document's current title and folder path. Its
     * files are not uploaded again, and a document the vault does not have is not created.
     */
    private suspend fun syncVaultMetadata(item: SyncQueueEntity, target: OutputTarget, ctx: RunContext): Outcome {
        if (target != OutputTarget.VAULT) return Outcome.Skipped
        val doc = source.document(item.documentId)?.takeIf { it.trashedAt == null } ?: return Outcome.Skipped
        // Written to the vault in this run since the move (its notes were copied): already current.
        if ((ctx.vaultDocWrittenAt[doc.id] ?: Long.MIN_VALUE) >= item.enqueuedAt) return Outcome.Skipped
        val client = ctx.vault()
        val id = VaultNames.docId(doc.id)
        if (!vaultHasDoc(client, id, ctx)) return Outcome.Skipped
        // The upsert replaces the text as well: send the current notes, or, when notes don't go to
        // the vault, the text it already stores (an earlier Notes -> Vault setting left it there).
        val markdown = if (OutputTarget.VAULT in ctx.prefs.notes) ctx.markdown(doc.id) else client.storedMarkdown(id) ?: return Outcome.Skipped
        val writtenAt = clock()
        client.putDoc(id, SyncPayloads.docUpsert(doc, source.folderPath(doc.folderId), markdown))
        ctx.vaultDocWrittenAt[doc.id] = writtenAt
        return Outcome.Done
    }

    // ------------------------------------------------------------------ folder placement

    /**
     * The document's directory, following a rename or move since the last copy. A directory that
     * holds another document's copies (one using the title before, or a deleted document whose
     * copies are kept) is never written into, renamed or emptied: the document gets its own
     * directory with a short id suffix instead.
     */
    private suspend fun documentDir(root: FolderNode, doc: DocumentMeta, ctx: RunContext): Placement {
        ctx.placements[doc.id]?.let { return it }
        val folderNames = source.folderNames(doc.folderId)
        var segments = FolderNaming.documentDir(folderNames, doc.title, doc.id)
        val previous = state.folderLocation(ctx.folderKey, doc.id)
        if (ctx.ownersOf(segments).any { it != doc.id }) {
            if (FolderWriter.findDir(root, segments) != null) {
                segments = FolderNaming.documentDir(folderNames, doc.title, doc.id, withId = true)
            } else {
                // Those copies are gone from the folder: the name is free again.
                ctx.forgetOwners(segments)
            }
        }
        var oldDir: FolderNode? = null
        val oldShared = previous != null && ctx.ownersOf(previous).any { it != doc.id }
        if (previous != null && previous != segments && !oldShared) {
            val old = FolderWriter.findDir(root, previous)
            if (old != null) {
                val parentSegments = segments.dropLast(1)
                val parent = if (previous.dropLast(1) == parentSegments) FolderWriter.findDir(root, parentSegments) else null
                if (parent != null && FolderWriter.renameDirInPlace(old, segments.last(), parent)) {
                    ctx.setLocation(doc.id, previous, segments)
                    return Placement(old, null, doc.id).also { ctx.placements[doc.id] = it }
                }
                oldDir = old
            }
        }
        val dir = FolderWriter.ensureDirs(root, segments)
        ctx.setLocation(doc.id, previous, segments)
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

    /**
     * State of one run under one set of destination settings. When the settings change during
     * the run, [following] continues with a new context that keeps the results so far and what
     * is still true of the Drive account, vault or folder that did not change.
     */
    private inner class RunContext(val prefs: DestinationPrefs, previous: RunContext? = null) {
        private val sameDrive: Boolean = previous != null && previous.prefs.driveAccount == prefs.driveAccount
        private val sameVault: Boolean = previous != null && previous.prefs.vaultUrl == prefs.vaultUrl
        private val sameFolder: Boolean = previous != null && previous.prefs.folderUri == prefs.folderUri
        var succeeded: Int = previous?.succeeded ?: 0
        var failed: Int = previous?.failed ?: 0
        var deferred: Int = previous?.deferred ?: 0
        /** Items skipped because they were waiting out their retry backoff. */
        var waitingBackoff: Int = previous?.waitingBackoff ?: 0
        var retryLater: Boolean = previous?.retryLater ?: false
        var lastSuccessAt: Long = previous?.lastSuccessAt ?: 0L
        var error: String? = previous?.error
        var driveBlocked: DriveException? = previous?.driveBlocked?.takeIf { sameDrive }
        /** Google asked to slow down: the next background run waits a little. */
        var slowDown: Boolean = previous?.slowDown ?: false
        var vaultBlocked: VaultException? = previous?.vaultBlocked?.takeIf { sameVault }
        /** True when this run's vault error came from the credentials hold, not from the vault. */
        var heldForCredentials: Boolean = sameVault && previous?.heldForCredentials == true
        var knownVaultDocs: HashSet<String>? = previous?.knownVaultDocs?.takeIf { sameVault }
        /** When this run last wrote each document's vault metadata (local document id -> [clock]). */
        val vaultDocWrittenAt: HashMap<String, Long> = previous?.vaultDocWrittenAt?.takeIf { sameVault } ?: HashMap()
        var folderBlocked: Exception? = previous?.folderBlocked?.takeIf { sameFolder }
        val placements: HashMap<String, Placement> = previous?.placements?.takeIf { sameFolder } ?: HashMap()
        var driveLayout: DriveLayout? = previous?.driveLayout?.takeIf { sameDrive }
            private set
        private var client: VaultClient? = previous?.client?.takeIf { sameVault }
        private var root: FolderNode? = previous?.root?.takeIf { sameFolder }
        /** Folder location -> documents whose copies are there; loaded on first use. */
        private var ownerMap: HashMap<List<String>, HashSet<String>>? = previous?.ownerMap?.takeIf { sameFolder }
        private var markdownCache: Pair<String, String>? = previous?.markdownCache
        /** The folder destination whose document locations this run reads and writes. */
        val folderKey: String get() = prefs.folderUri.orEmpty()
        val exports: ExportCache = previous?.exports ?: ExportCache(source)

        /** This context while the settings are still [current]; otherwise a new one for them. */
        fun following(current: DestinationPrefs): RunContext = if (current == prefs) this else RunContext(current, this)

        private fun owners(): HashMap<List<String>, HashSet<String>> = ownerMap ?: HashMap<List<String>, HashSet<String>>().also { map ->
            for ((docId, segments) in state.folderLocations(folderKey)) map.getOrPut(segments) { HashSet() } += docId
            ownerMap = map
        }

        fun ownersOf(segments: List<String>): Set<String> = owners()[segments].orEmpty()

        fun forgetOwners(segments: List<String>) {
            owners().remove(segments)?.forEach { state.setFolderLocation(folderKey, it, null) }
        }

        fun setLocation(docId: String, from: List<String>?, segments: List<String>) {
            state.setFolderLocation(folderKey, docId, segments)
            val map = owners()
            if (from != null) map[from]?.remove(docId)
            map.getOrPut(segments) { HashSet() } += docId
        }

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
            return DriveLayout(driveClient(account), state, prefs.driveShareLink).also { driveLayout = it }
        }

        fun vault(): VaultClient {
            vaultBlocked?.let { throw it }
            val now = clock()
            val lockedUntil = state.vaultLockedUntil
            if (lockedUntil > now) throw VaultException.Locked((lockedUntil - now + 999) / 1000)
            val refusedAt = state.vaultCredentialsRefusedAt
            if (refusedAt > 0 && now - refusedAt < CREDENTIALS_HOLD_MS) {
                heldForCredentials = true
                throw VaultException.WrongPassword()
            }
            client?.let { return it }
            val endpoint = endpointFor(prefs.vaultUrl) ?: throw VaultException.MissingLink()
            return vaultClient(endpoint).also { client = it }
        }

        fun folderRoot(): FolderNode {
            root?.let { return it }
            val uri = prefs.folderUri?.takeIf { it.isNotBlank() } ?: throw FolderRevokedException()
            return folders.root(uri).also { root = it }
        }
    }

    private enum class Outcome { Done, Skipped, NotReady }

    companion object {
        /**
         * `refId` of a NOTES item for Google Drive that only renames/moves the document's Drive
         * folder after a move or rename in the library: see [enqueueMoved].
         */
        const val DRIVE_MOVE_REF = "move"

        /**
         * `refId` of a NOTES item for the vault that only updates the document's metadata (title,
         * folder path) after a move or rename: see [enqueueMoved].
         */
        const val VAULT_METADATA_REF = "metadata"

        /**
         * Where an item may go under [prefs]: a folder move goes to Drive, and a metadata update to
         * the vault, while anything goes there.
         */
        internal fun targetsFor(kind: SyncKind, refId: String?, prefs: DestinationPrefs): Set<OutputTarget> = when {
            kind == SyncKind.NOTES && refId == DRIVE_MOVE_REF -> prefs.driveMoveTargets()
            kind == SyncKind.NOTES && refId == VAULT_METADATA_REF -> prefs.vaultMetadataTargets()
            else -> prefs.targetsFor(kind)
        }

        /** Maximum rows the vault returns from GET api/docs (the Worker's list limit). */
        internal const val VAULT_LIST_CAP = 2000
        private const val TAG = "InkwellSync"
        const val MAX_ATTEMPTS = 8
        /** Wait before the first retry of a failed item; doubles per attempt (as the worker's own backoff). */
        const val RETRY_BASE_MS = 30_000L
        private const val MAX_RETRY_DELAY_MS = 60L * 60 * 1000
        /** After the vault refused the password, no login is attempted for this long (the vault's lockout window). */
        const val CREDENTIALS_HOLD_MS = 15L * 60 * 1000
        const val MIME_PDF = "application/pdf"
        const val MIME_MARKDOWN = "text/markdown"
        const val MIME_AUDIO = "audio/mp4"
        private const val QUERY_LIMIT = 5000
        /** A document counts as changed when edited more than this after its last copy. */
        const val CHANGE_TOLERANCE_MS = 2_000L
        private const val KEEP_DONE_MS = 7L * 24 * 3600 * 1000
        private const val KEEP_FAILED_MS = 30L * 24 * 3600 * 1000

        /** How long an item that failed [attempts] times waits before its next try. */
        fun retryDelayMs(attempts: Int): Long =
            (RETRY_BASE_MS shl (attempts - 1).coerceIn(0, 20)).coerceAtMost(MAX_RETRY_DELAY_MS)

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
