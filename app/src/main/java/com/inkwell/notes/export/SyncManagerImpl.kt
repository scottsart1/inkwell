package com.inkwell.notes.export

import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.settings.SecretStore
import com.inkwell.notes.export.sync.AndroidNetworkState
import com.inkwell.notes.export.sync.Debouncer
import com.inkwell.notes.export.sync.DocumentFolderNode
import com.inkwell.notes.export.sync.DriveClient
import com.inkwell.notes.export.sync.DriveException
import com.inkwell.notes.export.sync.DriveLayout
import com.inkwell.notes.export.sync.DriveNames
import com.inkwell.notes.export.sync.DriveTokens
import com.inkwell.notes.export.sync.FolderRevokedException
import com.inkwell.notes.export.sync.FolderWriter
import com.inkwell.notes.export.sync.GraphSyncSource
import com.inkwell.notes.export.sync.PlayServicesDriveAuth
import com.inkwell.notes.export.sync.PrefsSyncStateStore
import com.inkwell.notes.export.sync.SafFolderAccess
import com.inkwell.notes.export.sync.SyncCheckFailure
import com.inkwell.notes.export.sync.SyncEngine
import com.inkwell.notes.export.sync.SyncKind
import com.inkwell.notes.export.sync.SyncRunReport
import com.inkwell.notes.export.sync.SyncScheduler
import com.inkwell.notes.export.sync.SyncScope
import com.inkwell.notes.export.sync.hasAnyTarget
import com.inkwell.notes.export.sync.includesMarkdown
import com.inkwell.notes.export.sync.includesPdf
import com.inkwell.notes.export.sync.syncNeeds
import com.inkwell.notes.export.sync.targetsFor
import com.inkwell.notes.export.sync.uses
import com.inkwell.notes.export.sync.withTargets
import com.inkwell.notes.export.sync.withoutDrive
import com.inkwell.notes.export.sync.withoutFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Copies notes, transcripts and audio to the destinations chosen in Settings (a SAF folder and/or
 * the user's Google Drive). This device always keeps the original; nothing here deletes local
 * data, and deleting a document never deletes its copies (sync is not backup).
 *
 * Work is queued in `sync_queue` and processed by [SyncEngine], in the background through
 * WorkManager ([com.inkwell.notes.export.sync.SyncWorker]) or right away by [syncNow].
 */
class SyncManagerImpl(private val graph: AppGraph) : SyncManager {
    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val stateStore by lazy { PrefsSyncStateStore(graph.context) }
    private val scheduler by lazy { SyncScheduler(graph.context) }
    private val debouncer = Debouncer(graph.appScope, EDIT_SETTLE_MS)

    internal val engine: SyncEngine by lazy {
        SyncEngine(
            queue = { graph.db.syncQueue() },
            source = GraphSyncSource(graph),
            prefs = { graph.settings.current.destinations },
            driveClient = { account -> driveClient(PlayServicesDriveAuth(graph.context) { account }) },
            folders = SafFolderAccess(graph.context),
            network = AndroidNetworkState(graph.context),
            state = stateStore,
            onFolderRevoked = { revokedUri -> clearFolderDestination(revokedUri) },
        )
    }

    init {
        graph.appScope.launch(Dispatchers.IO) { start() }
    }

    private suspend fun start() {
        try {
            forgetLegacyVault()
            refreshStatus()
            graph.appScope.launch { engine.running.collect { r -> _status.update { it.copy(running = r) } } }
            graph.appScope.launch(Dispatchers.IO) {
                graph.db.syncQueue().observePendingCount().collect { n -> _status.update { it.copy(pending = n) } }
            }
            graph.appScope.launch(Dispatchers.IO) { watchDestinations() }
            // Catch up on edits whose debounce was cut short by the process ending.
            delay(STARTUP_DELAY_MS)
            val prefs = graph.settings.current.destinations
            if (prefs.autoSync && prefs.hasAnyTarget) {
                engine.enqueueChangedDocuments(prefs)
                kick()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync start-up failed: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ SyncManager

    override fun onDocumentChanged(docId: String) {
        val prefs = graph.settings.current.destinations
        if (!prefs.autoSync || prefs.notes.isEmpty()) return
        debouncer.submit(docId) {
            withContext(Dispatchers.IO) {
                val now = graph.settings.current.destinations
                if (now.autoSync && now.notes.isNotEmpty()) {
                    safely { engine.enqueue(docId, SyncKind.NOTES, now.notes) }
                    kick()
                }
            }
        }
    }

    override fun onDocumentMoved(docId: String) {
        val prefs = graph.settings.current.destinations
        if (!prefs.autoSync || !prefs.hasAnyTarget) return
        // Its own key: an edit settling meanwhile must not replace the move, nor the move the edit.
        debouncer.submit(movedKey(docId)) {
            withContext(Dispatchers.IO) {
                val now = graph.settings.current.destinations
                if (now.autoSync && now.hasAnyTarget) {
                    safely { engine.enqueueMoved(docId, now) }
                    kick()
                }
            }
        }
    }

    override fun onTranscriptReady(docId: String, recordingId: String) = enqueueRecording(docId, recordingId, SyncKind.TRANSCRIPT)

    override fun onRecordingFinished(docId: String, recordingId: String) = enqueueRecording(docId, recordingId, SyncKind.AUDIO)

    private fun enqueueRecording(docId: String, recordingId: String, kind: SyncKind) {
        graph.appScope.launch(Dispatchers.IO) {
            val prefs = graph.settings.current.destinations
            val targets = prefs.targetsFor(kind)
            if (targets.isEmpty()) return@launch
            safely { engine.enqueue(docId, kind, targets, recordingId) }
            // With automatic sync off the item waits for "Sync now".
            if (prefs.autoSync) kick()
        }
    }

    override fun onDocumentDeleted(docId: String) {
        debouncer.cancel(docId)
        debouncer.cancel(movedKey(docId))
        graph.appScope.launch(Dispatchers.IO) {
            // Copies in the folder and in Drive stay: deleting locally never deletes them. The
            // folder location is kept too, so a later document with the same title gets its own
            // directory instead of writing over these last copies.
            safely { graph.db.syncQueue().dropOpenForDocument(docId) }
        }
    }

    override suspend fun syncNow(): SyncStatus = withContext(Dispatchers.IO) {
        val prefs = graph.settings.current.destinations
        val dao = graph.db.syncQueue()
        safely {
            dao.retryFailed()
            if (dao.pendingCount() == 0) engine.enqueueEverything(prefs) else engine.enqueueChangedDocuments(prefs)
        }
        val report = try {
            // The user asked: items waiting out a retry backoff go now too.
            engine.run(ignoreBackoff = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync now failed: ${e.javaClass.simpleName}")
            stateStore.lastError = "Sync stopped unexpectedly. Inkwell will try again."
            SyncRunReport(retryLater = true)
        }
        afterRun(report)
        // Anything still waiting (offline, Wi-Fi only, retry) continues in the background.
        kick()
        val pending = runCatching { dao.pendingCount() }.getOrDefault(_status.value.pending)
        _status.update { it.copy(pending = pending) }
        _status.value
    }

    override suspend fun testDrive(): Result<String> = withContext(Dispatchers.IO) {
        val account = graph.settings.current.destinations.driveAccount
            ?: return@withContext Result.failure(SyncCheckFailure("Connect Google Drive first."))
        checkDrive(driveClient(PlayServicesDriveAuth(graph.context) { account }), account)
    }

    /**
     * Makes sure the "Inkwell" folder exists in the connected Drive and reports where copies go.
     * Also releases items that waited for Drive (setup, sign-in).
     */
    private suspend fun checkDrive(client: DriveClient, account: String): Result<String> = try {
        DriveLayout(client, stateStore, graph.settings.current.destinations.driveShareLink).root()
        if (stateStore.lastError != null) stateStore.lastError = null
        refreshStatus()
        if (graph.settings.current.destinations.autoSync) kick()
        Result.success("Connected as $account. Copies go to My Drive › ${DriveNames.ROOT_FOLDER}.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: DriveException) {
        Result.failure(SyncCheckFailure(e.message ?: "Google Drive didn't answer.", e))
    }

    override suspend fun testFolder(treeUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val doc = DocumentFile.fromTreeUri(graph.context, treeUri)
                ?: return@withContext Result.failure(SyncCheckFailure("Inkwell can't open this folder."))
            FolderWriter.probe(DocumentFolderNode(graph.context, doc), System.currentTimeMillis())
            val name = runCatching { doc.name }.getOrNull()?.takeIf { it.isNotBlank() }
            Result.success(if (name != null) "Inkwell can write to $name." else "Inkwell can write to this folder.")
        } catch (e: FolderRevokedException) {
            Result.failure(SyncCheckFailure("Inkwell doesn't have permission to write here. Choose the folder again.", e))
        } catch (e: SecurityException) {
            Result.failure(SyncCheckFailure("Inkwell doesn't have permission to write here. Choose the folder again.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(SyncCheckFailure(e.message?.takeIf { it.isNotBlank() } ?: "Inkwell can't write to this folder.", e))
        }
    }

    // ------------------------------------------------------------------ app-internal API (settings, worker)

    /** Items that gave up after repeated failures ("Sync now" retries them). */
    fun observeFailedCount(): Flow<Int> = runCatching { graph.db.syncQueue().observeFailedCount() }.getOrDefault(emptyFlow())

    /**
     * Google granted Drive access ([token], from the settings screen): finds out which account it
     * is, saves it, makes sure the Inkwell folder exists, and switches Drive on for [enableFor].
     * Returns the account or a failure ready to show.
     */
    suspend fun connectDrive(token: String, enableFor: SyncKind?): Result<String> = withContext(Dispatchers.IO) {
        try {
            val oneShot = driveClient { _ -> token }
            val email = oneShot.accountEmail() ?: throw DriveException.BadResponse()
            val previous = graph.settings.current.destinations.driveAccount
            if (previous != null && !previous.equals(email, ignoreCase = true)) stateStore.clearDrive()
            DriveLayout(oneShot, stateStore, graph.settings.current.destinations.driveShareLink).root()
            graph.settings.update { s ->
                var d = s.destinations.copy(driveAccount = email)
                if (enableFor != null) d = d.withTargets(enableFor, d.targetsFor(enableFor) + OutputTarget.DRIVE)
                s.copy(destinations = d)
            }
            // Whatever waited for Drive (sign-in, setup) goes now.
            graph.db.syncQueue().retryFailedForTarget(OutputTarget.DRIVE.name)
            if (stateStore.lastError != null) stateStore.lastError = null
            refreshStatus()
            if (graph.settings.current.destinations.autoSync) kick()
            Result.success(email)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveException) {
            Result.failure(SyncCheckFailure(e.message ?: "Google Drive couldn't be connected.", e))
        } catch (e: Exception) {
            Log.w(TAG, "Drive connect failed: ${e.javaClass.simpleName}")
            Result.failure(SyncCheckFailure("Google Drive couldn't be connected. Try again.", e))
        }
    }

    /**
     * Stops copying to Drive and withdraws Inkwell's access. Files already in Drive stay there.
     */
    suspend fun disconnectDrive() = withContext(Dispatchers.IO) {
        val account = graph.settings.current.destinations.driveAccount
        // Withdraw a shared link while Inkwell still has access: after revoking it no longer can.
        val shared = runCatching { stateStore.driveSharedRootId }.getOrNull()
        if (account != null && shared != null) {
            try {
                driveClient(PlayServicesDriveAuth(graph.context) { account }).stopSharing(shared)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't withdraw the Drive link: ${e.javaClass.simpleName}")
            }
        }
        graph.settings.update { it.copy(destinations = it.destinations.withoutDrive()) }
        stateStore.clearDrive()
        safely { graph.db.syncQueue().dropAllForTarget(OutputTarget.DRIVE.name) }
        if (account != null) {
            try {
                PlayServicesDriveAuth.revoke(graph.context, account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Access can still be removed at myaccount.google.com › Security › Third-party access.
                Log.w(TAG, "Couldn't revoke Drive access: ${e.javaClass.simpleName}")
            }
        }
        if (stateStore.lastError != null) stateStore.lastError = null
        refreshStatus()
    }

    private fun driveClient(tokens: DriveTokens) = DriveClient(graph.http, tokens)

    /** The Inkwell folder in Drive on the web (My Drive until Inkwell has made its folder). */
    /** The Inkwell folder's link for another computer, once the folder exists; null before. */
    fun driveFolderLink(): String? =
        runCatching { stateStore.driveRootId }.getOrNull()?.takeIf { it.isNotBlank() }?.let { DriveClient.folderUrl(it) }

    /** True while the Inkwell folder is shared with anyone who has its link. */
    fun driveLinkShared(): Boolean =
        runCatching { stateStore.driveSharedRootId != null && stateStore.driveSharedRootId == stateStore.driveRootId }.getOrDefault(false)

    /**
     * Shares the Inkwell folder by link, or withdraws the link, right away (not at the next copy).
     * Returns a failure ready to show when Drive refused.
     */
    suspend fun applyDriveSharing(): Result<Unit> = withContext(Dispatchers.IO) {
        val prefs = graph.settings.current.destinations
        val account = prefs.driveAccount ?: return@withContext Result.success(Unit)
        try {
            DriveLayout(driveClient(PlayServicesDriveAuth(graph.context) { account }), stateStore, prefs.driveShareLink).root()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveException) {
            Result.failure(SyncCheckFailure(e.message ?: "Google Drive didn't answer.", e))
        }
    }

    fun driveFolderUrl(): String =
        runCatching { stateStore.driveRootId }.getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { "https://drive.google.com/drive/folders/$it" }
            ?: "https://drive.google.com/drive/my-drive"

    /** Removes what the notes vault (removed) left behind: queued uploads, password, session, state. */
    private suspend fun forgetLegacyVault() {
        safely { graph.db.syncQueue().dropAllForTarget(LEGACY_VAULT_TARGET) }
        safely { SecretStore.LEGACY_VAULT_SECRETS.forEach { if (graph.secrets.has(it)) graph.secrets.remove(it) } }
        safely { stateStore.forgetLegacyVault() }
    }

    /** One background pass (called by the worker). */
    internal suspend fun runQueue(scope: SyncScope): SyncRunReport {
        val report = engine.run(scope)
        afterRun(report)
        return report
    }

    /** Schedules background runs for whatever is waiting in the queue. */
    fun kick() {
        graph.appScope.launch(Dispatchers.IO) {
            safely {
                val items = graph.db.syncQueue().pendingKindsAndTargets()
                val needs = syncNeeds(items, graph.settings.current.destinations)
                if (needs.any) scheduler.schedule(needs)
            }
        }
    }

    private fun afterRun(report: SyncRunReport) {
        refreshStatus()
        if (report.slowDown) scheduler.scheduleAfter(SLOW_DOWN_MS)
    }

    private fun refreshStatus() {
        val lastSuccess = runCatching { stateStore.lastSuccessAt }.getOrDefault(0)
        val lastError = runCatching { stateStore.lastError }.getOrNull()
        _status.update { it.copy(lastSuccessAt = lastSuccess, lastError = lastError) }
    }

    /** Clears the folder destination, unless the user has chosen another folder than [revokedUri] since. */
    private suspend fun clearFolderDestination(revokedUri: String?) {
        graph.settings.update { s ->
            if (s.destinations.folderUri != revokedUri) s else s.copy(destinations = s.destinations.withoutFolder())
        }
    }

    // ------------------------------------------------------------------ destination changes

    private suspend fun watchDestinations() {
        var previous = graph.settings.current.destinations
        graph.settings.settings.map { it.destinations }.distinctUntilChanged().debounce(SETTINGS_SETTLE_MS).collect { next ->
            if (next != previous) {
                val old = previous
                previous = next
                safely { onDestinationsChanged(old, next) }
            }
        }
    }

    private suspend fun onDestinationsChanged(old: DestinationPrefs, new: DestinationPrefs) {
        val dao = graph.db.syncQueue()
        var enqueued = 0
        for (kind in SyncKind.entries) {
            val before = old.targetsFor(kind)
            val after = new.targetsFor(kind)
            for (removed in before - after) dao.dropOpenFor(kind.name, removed.name)
            for (added in after - before) enqueued += engine.enqueueEverything(new, onlyKind = kind, onlyTarget = added)
        }
        if (new.folderUri != old.folderUri) {
            // Document locations are kept per folder (see PrefsSyncStateStore): coming back to a
            // folder later still knows which directories hold deleted documents' last copies.
            if (old.folderUri != null && new.folderUri != null) enqueued += engine.enqueueEverything(new, onlyTarget = OutputTarget.FOLDER)
        }
        val accountChanged = !new.driveAccount.equals(old.driveAccount, ignoreCase = true)
        if (accountChanged && old.driveAccount != null && new.driveAccount != null) {
            // Another Google account: its Drive has none of the copies yet.
            enqueued += engine.enqueueEverything(new, onlyTarget = OutputTarget.DRIVE)
        }
        val newFormats = (new.notesFormat.includesPdf && !old.notesFormat.includesPdf) ||
            (new.notesFormat.includesMarkdown && !old.notesFormat.includesMarkdown)
        if (newFormats) enqueued += engine.enqueueEverything(new, onlyKind = SyncKind.NOTES)
        if (new.autoSync && !old.autoSync) enqueued += engine.enqueueChangedDocuments(new)
        if (!new.autoSync && old.autoSync) scheduler.cancelAll()
        val conditionsChanged = enqueued > 0 || new.autoSync != old.autoSync ||
            new.audioOnUnmeteredOnly != old.audioOnUnmeteredOnly || accountChanged || new.folderUri != old.folderUri
        if (new.autoSync && conditionsChanged) kick()
    }

    private suspend inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync bookkeeping failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "InkwellSync"
        /** Debounce key of a document's move; its edits use the document id itself. */
        fun movedKey(docId: String) = "$docId#moved"
        /** Notes are copied this long after the last edit of a document. */
        const val EDIT_SETTLE_MS = 30_000L
        const val SETTINGS_SETTLE_MS = 1_200L
        const val STARTUP_DELAY_MS = 15_000L
        /** Wait after Google Drive asked Inkwell to slow down. */
        const val SLOW_DOWN_MS = 5L * 60 * 1000
        /** `sync_queue.target` of the removed notes vault. */
        const val LEGACY_VAULT_TARGET = "VAULT"
    }
}
