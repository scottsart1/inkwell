package com.inkwell.notes.export.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.export.ExportFormat
import com.inkwell.notes.export.ExportOptions
import java.io.File

/** [SyncSource] over the app graph: library, export service and the recordings tables. */
internal class GraphSyncSource(private val graph: AppGraph) : SyncSource {
    override suspend fun document(docId: String): DocumentMeta? = graph.library.getDocument(docId)

    override suspend fun allDocuments(): List<DocumentMeta> = graph.db.documents().getAll().map { it.toModel() }

    override suspend fun folderPath(folderId: String?): String = if (folderId == null) "" else graph.library.folderPath(folderId)

    override suspend fun folderNames(folderId: String?): List<String> {
        val names = ArrayList<String>()
        val seen = HashSet<String>()
        var id = folderId
        while (id != null && seen.add(id) && names.size < MAX_DEPTH) {
            val folder = graph.db.folders().get(id) ?: break
            names += folder.name
            id = folder.parentId
        }
        return names.asReversed()
    }

    override suspend fun markdown(docId: String): String = graph.export.documentMarkdown(docId, includeTranscripts = false)

    override suspend fun exportPdf(docId: String): File = graph.export.export(docId, ExportOptions(ExportFormat.PDF))

    override suspend fun exportAudio(recordingId: String): File = graph.export.exportRecording(recordingId)

    override fun isTemporary(file: File): Boolean = runCatching {
        // Only ever delete exports in the cache, never an original (a single-segment recording
        // may be handed back as-is from the document folder).
        val path = file.canonicalPath
        val cache = graph.files.cacheDir.canonicalPath + File.separator
        val docs = graph.files.root.canonicalPath + File.separator
        path.startsWith(cache) && !path.startsWith(docs)
    }.getOrDefault(false)

    override suspend fun recording(recordingId: String): Recording? = graph.db.recordings().get(recordingId)?.toModel()

    override suspend fun recordings(docId: String): List<Recording> = graph.db.recordings().forDocument(docId).map { it.toModel() }

    override suspend fun transcript(recordingId: String): StoredTranscript? {
        val rows = graph.db.transcripts().forRecording(recordingId)
        if (rows.isEmpty()) return null
        val source = rows.firstOrNull { it.source.isNotBlank() }?.source ?: "unknown"
        return StoredTranscript(rows.map { it.toModel() }, source)
    }

    override suspend fun markSynced(docId: String, at: Long) {
        try {
            // Re-read right before writing so a concurrent edit (title, tags...) is never undone.
            val current = graph.library.getDocument(docId) ?: return
            graph.library.updateDocument(current.copy(lastSyncedAt = at))
        } catch (e: Exception) {
            Log.w("InkwellSync", "Couldn't record the sync time: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}

/** Sync bookkeeping in a private preferences file (no secrets, no note content). */
internal class PrefsSyncStateStore(context: Context) : SyncStateStore {
    private val prefs = context.getSharedPreferences("inkwell_sync", Context.MODE_PRIVATE)
    private val locations = context.getSharedPreferences("inkwell_sync_locations", Context.MODE_PRIVATE)
    private val driveFolders = context.getSharedPreferences("inkwell_sync_drive", Context.MODE_PRIVATE)

    override var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_SUCCESS, value).apply()

    override var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, value) }.apply()

    override var driveRootId: String?
        get() = prefs.getString(KEY_DRIVE_ROOT, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_DRIVE_ROOT) else putString(KEY_DRIVE_ROOT, value) }.apply()

    override fun folderLocation(docId: String): List<String>? =
        locations.getString(docId, null)?.split(SEP)?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    override fun setFolderLocation(docId: String, segments: List<String>?) {
        locations.edit().apply { if (segments == null) remove(docId) else putString(docId, segments.joinToString(SEP)) }.apply()
    }

    fun clearAllLocations() = locations.edit().clear().apply()

    override fun driveDocFolder(docId: String): String? = driveFolders.getString(docId, null)

    override fun setDriveDocFolder(docId: String, folderId: String?) {
        driveFolders.edit().apply { if (folderId == null) remove(docId) else putString(docId, folderId) }.apply()
    }

    override fun clearDrive() {
        driveRootId = null
        driveFolders.edit().clear().apply()
    }

    /** Drops what older versions kept about the notes vault. */
    fun forgetLegacyVault() {
        if (prefs.contains(LEGACY_LOCKED_UNTIL) || prefs.contains(LEGACY_REFUSED_AT)) {
            prefs.edit().remove(LEGACY_LOCKED_UNTIL).remove(LEGACY_REFUSED_AT).apply()
        }
    }

    private companion object {
        const val KEY_LAST_SUCCESS = "lastSuccessAt"
        const val KEY_LAST_ERROR = "lastError"
        const val KEY_DRIVE_ROOT = "driveRootId"
        const val LEGACY_LOCKED_UNTIL = "vaultLockedUntil"
        const val LEGACY_REFUSED_AT = "vaultCredentialsRefusedAt"
        const val SEP = "\u001F"
    }
}

internal class AndroidNetworkState(context: Context) : NetworkState {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun caps(): NetworkCapabilities? = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()

    override fun isOnline(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun isUnmetered(): Boolean {
        val c = caps() ?: return false
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    }
}
