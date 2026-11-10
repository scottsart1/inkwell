package com.inkwell.notes.export.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.settings.SecretStore
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
            // Only this column: writing the whole document back could undo a concurrent edit
            // (title, folder, tags...) or bring back a document deleted meanwhile.
            graph.db.documents().setLastSyncedAt(docId, at)
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

    override var driveSharedRootId: String?
        get() = prefs.getString(KEY_DRIVE_SHARED, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_DRIVE_SHARED) else putString(KEY_DRIVE_SHARED, value) }.apply()

    override var vaultLockedUntil: Long
        get() = prefs.getLong(KEY_LOCKED_UNTIL, 0)
        set(value) = prefs.edit().putLong(KEY_LOCKED_UNTIL, value).apply()

    override var vaultCredentialsRefusedAt: Long
        get() = prefs.getLong(KEY_REFUSED_AT, 0)
        set(value) = prefs.edit().putLong(KEY_REFUSED_AT, value).apply()

    /** Locations live under "<folder tree URI><SEP><document id>", so each folder keeps its own. */
    private fun key(folder: String, docId: String) = folder + SEP + docId

    @Volatile private var legacyAdopted = false

    /**
     * Locations saved before they were kept per folder (key = document id) belong to the folder
     * chosen at that time, which is the one sync uses first after the update: [folder] takes them.
     */
    @Synchronized
    private fun adoptLegacy(folder: String) {
        if (legacyAdopted) return
        legacyAdopted = true
        val legacy = locations.all.filterKeys { !it.contains(SEP) }
        if (legacy.isEmpty()) return
        locations.edit().apply {
            for ((docId, value) in legacy) {
                remove(docId)
                if (value is String) putString(key(folder, docId), value)
            }
        }.apply()
    }

    override fun folderLocation(folder: String, docId: String): List<String>? {
        adoptLegacy(folder)
        return decode(locations.getString(key(folder, docId), null))
    }

    override fun folderLocations(folder: String): Map<String, List<String>> {
        adoptLegacy(folder)
        val prefix = folder + SEP
        return locations.all.mapNotNull { (k, value) ->
            if (!k.startsWith(prefix)) return@mapNotNull null
            decode(value as? String)?.let { k.substring(prefix.length) to it }
        }.toMap()
    }

    private fun decode(stored: String?): List<String>? =
        stored?.split(SEP)?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    override fun setFolderLocation(folder: String, docId: String, segments: List<String>?) {
        adoptLegacy(folder)
        val k = key(folder, docId)
        locations.edit().apply { if (segments == null) remove(k) else putString(k, segments.joinToString(SEP)) }.apply()
    }

    override fun driveDocFolder(docId: String): String? = driveFolders.getString(docId, null)

    override fun setDriveDocFolder(docId: String, folderId: String?) {
        driveFolders.edit().apply { if (folderId == null) remove(docId) else putString(docId, folderId) }.apply()
    }

    override fun clearDrive() {
        driveRootId = null
        driveSharedRootId = null
        driveFolders.edit().clear().apply()
    }

    private companion object {
        const val KEY_LAST_SUCCESS = "lastSuccessAt"
        const val KEY_LAST_ERROR = "lastError"
        const val KEY_DRIVE_ROOT = "driveRootId"
        const val KEY_DRIVE_SHARED = "driveSharedRootId"
        const val KEY_LOCKED_UNTIL = "vaultLockedUntil"
        const val KEY_REFUSED_AT = "vaultCredentialsRefusedAt"
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

/**
 * The saved vault password belongs to the vault link it was entered for: it is never sent to
 * another link (a new vault, a typo, a look-alike host). A password saved before it was tied to
 * a link, or while no link was set, belongs to the saved link. The same vault on the other host
 * of its site (`www.` added or dropped, see [VaultRedirect]) counts as the same link. Changing
 * the saved link never forgets the password ([keepWith] ties it to the link it was for): it is
 * shown as saved ([savedFor]) and sent ([storedFor]) only while its link is the saved one again,
 * so a half-edited or mistyped link costs nothing. Only "Forget password" ([forget]) removes it.
 */
internal object VaultPassword {
    /** True when a password tied to [boundTo] (a fingerprint; null = untied) may be sent to [endpoint]. */
    fun fits(boundTo: String?, endpoint: VaultEndpoint, savedLink: String): Boolean {
        val host = endpoint.base.host
        val sibling = runCatching { endpoint.withHost(if (host.startsWith("www.")) host.removePrefix("www.") else "www.$host") }.getOrNull()
        val same = listOfNotNull(endpoint, sibling)
        return if (boundTo != null) same.any { it.fingerprint == boundTo } else VaultUrl.endpointOrNull(savedLink) in same
    }

    /**
     * The link a password tied to [boundTo] (null = untied) belongs to once the saved link moves
     * away from [oldLink]: an untied one stays with [oldLink], or, when no link was set, stays
     * untied (it is for the next link entered).
     */
    fun boundAcross(boundTo: String?, oldLink: String): String? =
        boundTo ?: VaultUrl.endpointOrNull(oldLink)?.fingerprint

    /** The saved password if it may be sent to [endpoint]; [savedLink] is the vault link in settings. */
    @Synchronized
    fun storedFor(secrets: SecretStore, endpoint: VaultEndpoint, savedLink: String): String? {
        val password = secrets.get(SecretStore.VAULT_PASSWORD) ?: return null
        return password.takeIf { fits(secrets.get(SecretStore.VAULT_PASSWORD_VAULT), endpoint, savedLink) }
    }

    /** True when a saved password applies to [savedLink] (no link set: when one is saved at all). */
    @Synchronized
    fun savedFor(secrets: SecretStore, savedLink: String): Boolean {
        if (!secrets.has(SecretStore.VAULT_PASSWORD)) return false
        val endpoint = VaultUrl.endpointOrNull(savedLink) ?: return true
        return fits(secrets.get(SecretStore.VAULT_PASSWORD_VAULT), endpoint, savedLink)
    }

    /** Saves [password] for [endpoint] (null: for whichever link is saved when it is first used). */
    @Synchronized
    fun save(secrets: SecretStore, password: String, endpoint: VaultEndpoint?) {
        secrets.put(SecretStore.VAULT_PASSWORD, password)
        secrets.put(SecretStore.VAULT_PASSWORD_VAULT, endpoint?.fingerprint)
    }

    /**
     * The saved link is about to change from [oldLink]: the password stays saved, tied to its
     * link (see [boundAcross]), so no other link receives it and going back to that link restores it.
     */
    @Synchronized
    fun keepWith(secrets: SecretStore, oldLink: String) {
        if (!secrets.has(SecretStore.VAULT_PASSWORD) || secrets.has(SecretStore.VAULT_PASSWORD_VAULT)) return
        boundAcross(null, oldLink)?.let { secrets.put(SecretStore.VAULT_PASSWORD_VAULT, it) }
    }

    /** The same vault answers from [to] now (VaultRedirect): its password follows it. */
    @Synchronized
    fun moved(secrets: SecretStore, from: VaultEndpoint, to: VaultEndpoint) {
        if (secrets.get(SecretStore.VAULT_PASSWORD_VAULT) == from.fingerprint) secrets.put(SecretStore.VAULT_PASSWORD_VAULT, to.fingerprint)
    }

    @Synchronized
    fun forget(secrets: SecretStore) {
        secrets.remove(SecretStore.VAULT_PASSWORD)
        secrets.remove(SecretStore.VAULT_PASSWORD_VAULT)
        secrets.remove(SecretStore.VAULT_SESSION)
    }
}

/** Vault password and the cached session token, both in [SecretStore] (Keystore-encrypted). */
internal class SecretVaultCredentials(
    private val secrets: SecretStore,
    private val endpoint: VaultEndpoint,
    /** The vault link in settings, for a password not tied to a link yet (see [VaultPassword]). */
    private val savedLink: () -> String = { "" },
) : VaultCredentials {
    override fun password(): String? = VaultPassword.storedFor(secrets, endpoint, savedLink())

    override fun session(): VaultSession? = VaultSessionCodec.decode(secrets.get(SecretStore.VAULT_SESSION), endpoint.fingerprint)

    override fun saveSession(session: VaultSession?) {
        secrets.put(SecretStore.VAULT_SESSION, session?.let { VaultSessionCodec.encode(it, endpoint.fingerprint) })
    }
}
