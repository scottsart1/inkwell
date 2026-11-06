package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingStatus
import com.inkwell.notes.core.model.TranscriptStatus
import com.inkwell.notes.core.storage.db.SyncQueueDao
import com.inkwell.notes.core.storage.db.SyncQueueEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory [SyncQueueDao] with the same semantics as the Room queries. */
class FakeSyncQueueDao : SyncQueueDao {
    private var nextId = 1L
    val rows = MutableStateFlow<List<SyncQueueEntity>>(emptyList())

    val all: List<SyncQueueEntity> get() = rows.value
    fun pendingRows() = all.filter { it.status == SyncItemStatus.PENDING }

    override suspend fun enqueue(item: SyncQueueEntity): Long {
        val id = nextId++
        rows.value = rows.value + item.copy(rowId = id)
        return id
    }

    override suspend fun pending(limit: Int): List<SyncQueueEntity> =
        pendingRows().sortedBy { it.enqueuedAt }.take(limit)

    override fun observePendingCount(): Flow<Int> = rows.map { list -> list.count { it.status == SyncItemStatus.PENDING } }

    override fun observeRecent(limit: Int): Flow<List<SyncQueueEntity>> = rows.map { it.sortedByDescending { r -> r.enqueuedAt }.take(limit) }

    override suspend fun update(item: SyncQueueEntity) {
        rows.value = rows.value.filter { it.rowId != item.rowId } + item
    }

    override suspend fun pruneDone(before: Long) {
        rows.value = rows.value.filterNot { it.status == SyncItemStatus.DONE && it.enqueuedAt < before }
    }

    override suspend fun dropPendingDuplicates(docId: String, kind: String, target: String) {
        rows.value = rows.value.filterNot { it.documentId == docId && it.kind == kind && it.target == target && it.status == SyncItemStatus.PENDING }
    }

    override suspend fun dropOpenDuplicates(docId: String, kind: String, target: String, refId: String?) {
        rows.value = rows.value.filterNot {
            it.documentId == docId && it.kind == kind && it.target == target && it.refId == refId &&
                (it.status == SyncItemStatus.PENDING || it.status == SyncItemStatus.FAILED)
        }
    }

    override suspend fun pendingCount(): Int = pendingRows().size

    override fun observeFailedCount(): Flow<Int> = rows.map { list -> list.count { it.status == SyncItemStatus.FAILED } }

    override suspend fun setOutcome(rowId: Long, status: String, attempts: Int, lastError: String?) {
        rows.value = rows.value.map { if (it.rowId == rowId) it.copy(status = status, attempts = attempts, lastError = lastError) else it }
    }

    override suspend fun dropOpenForDocument(docId: String) {
        rows.value = rows.value.filterNot { it.documentId == docId && it.status != SyncItemStatus.DONE }
    }

    override suspend fun dropOpenFor(kind: String, target: String) {
        rows.value = rows.value.filterNot { it.kind == kind && it.target == target && it.status != SyncItemStatus.DONE }
    }

    override suspend fun failPendingForTarget(target: String, error: String): Int {
        var n = 0
        rows.value = rows.value.map {
            if (it.target == target && it.status == SyncItemStatus.PENDING) {
                n++
                it.copy(status = SyncItemStatus.FAILED, lastError = error)
            } else it
        }
        return n
    }

    override suspend fun retryFailed(): Int = retryWhere { true }

    override suspend fun retryFailedForTarget(target: String): Int = retryWhere { it.target == target }

    override suspend fun dropAllForTarget(target: String): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.target == target }
        return before - rows.value.size
    }

    private fun retryWhere(which: (SyncQueueEntity) -> Boolean): Int {
        var n = 0
        rows.value = rows.value.map {
            if (it.status == SyncItemStatus.FAILED && which(it)) {
                n++
                it.copy(status = SyncItemStatus.PENDING, attempts = 0, lastError = null)
            } else it
        }
        return n
    }

    override suspend fun pruneFailed(before: Long) {
        rows.value = rows.value.filterNot { it.status == SyncItemStatus.FAILED && it.enqueuedAt < before }
    }
}

/** [SyncSource] over plain maps. */
class FakeSource(private val tmp: File) : SyncSource {
    val docs = LinkedHashMap<String, DocumentMeta>()
    val folders = HashMap<String, Pair<String, String?>>() // id -> name, parent
    val markdowns = HashMap<String, String>()
    val recordings = LinkedHashMap<String, Recording>()
    val transcripts = HashMap<String, StoredTranscript>()
    val synced = HashMap<String, Long>()
    var pdfExports = 0
    var audioExports = 0
    var failExport: Throwable? = null

    override suspend fun document(docId: String) = docs[docId]
    override suspend fun allDocuments() = docs.values.toList()

    override suspend fun folderPath(folderId: String?): String = folderNames(folderId).joinToString(" / ")

    override suspend fun folderNames(folderId: String?): List<String> {
        val out = ArrayList<String>()
        var id = folderId
        while (id != null) {
            val (name, parent) = folders[id] ?: break
            out += name
            id = parent
        }
        return out.asReversed()
    }

    override suspend fun markdown(docId: String) = markdowns[docId] ?: "# ${docs[docId]?.title}\n"

    override suspend fun exportPdf(docId: String): File {
        failExport?.let { throw it }
        pdfExports++
        return File(tmp, "export-$docId-$pdfExports.pdf").apply { writeBytes(pdfBytes(docId)) }
    }

    override suspend fun exportAudio(recordingId: String): File {
        audioExports++
        return File(tmp, "export-$recordingId.m4a").apply { writeBytes(audioBytes(recordingId)) }
    }

    override fun isTemporary(file: File) = file.parentFile == tmp

    override suspend fun recording(recordingId: String) = recordings[recordingId]
    override suspend fun recordings(docId: String) = recordings.values.filter { it.documentId == docId }
    override suspend fun transcript(recordingId: String) = transcripts[recordingId]

    override suspend fun markSynced(docId: String, at: Long) {
        synced[docId] = at
        docs[docId]?.let { docs[docId] = it.copy(lastSyncedAt = at) }
    }

    companion object {
        fun pdfBytes(docId: String) = "%PDF-1.7 fake for $docId".encodeToByteArray()
        fun audioBytes(recId: String) = ByteArray(2048) { (it + recId.length).toByte() }
    }
}

class FakeNetwork(var online: Boolean = true, var unmetered: Boolean = true) : NetworkState {
    override fun isOnline() = online
    override fun isUnmetered() = unmetered
}

/** [FolderNode] over java.io, behaving like a SAF provider (unique names, optional rename support). */
class JavaFileNode(file: File, private val supportsRename: Boolean = true) : FolderNode {
    var file: File = file
        private set
    override val name: String get() = file.name
    override val isDirectory: Boolean get() = file.isDirectory

    override fun children(): List<FolderNode> = file.listFiles()?.sortedBy { it.name }?.map { JavaFileNode(it, supportsRename) } ?: emptyList()

    override fun createDirectory(name: String): FolderNode? {
        val target = unique(name)
        return if (target.mkdir()) JavaFileNode(target, supportsRename) else null
    }

    override fun createFile(mimeType: String, name: String): FolderNode? {
        val target = unique(name)
        return if (target.createNewFile()) JavaFileNode(target, supportsRename) else null
    }

    override fun openOutput(): OutputStream = FileOutputStream(file)
    override fun openInput(): InputStream = FileInputStream(file)
    override fun delete(): Boolean = if (file.isDirectory) file.list()?.isEmpty() == true && file.delete() else file.delete()

    override fun renameTo(name: String): Boolean {
        if (!supportsRename) return false
        val target = File(file.parentFile, name)
        if (target.exists()) return false
        val ok = file.renameTo(target)
        if (ok) file = target
        return ok
    }

    /** Like SAF providers: "name (1).ext" when the name is taken. */
    private fun unique(name: String): File {
        var candidate = File(file, name)
        var i = 1
        while (candidate.exists()) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            candidate = File(file, "$stem ($i)$ext")
            i++
        }
        return candidate
    }
}

/**
 * An in-memory Google Drive that behaves like the v3 API as Inkwell uses it: ids, parents, app
 * properties, the trash, conversion to Google Docs, and "not found" for deleted ids.
 */
class FakeDrive : DriveApi {
    class Node(
        val id: String,
        var name: String,
        val mimeType: String,
        var parents: List<String>,
        val properties: MutableMap<String, String>,
        var content: ByteArray? = null,
        /** For converted files: the media type that was uploaded. */
        var uploadedAs: String? = null,
        var trashed: Boolean = false,
        var versions: Int = 1,
    ) {
        fun toItem() = DriveItem(id, name, mimeType, parents, properties.toMap())
        val isFolder get() = mimeType == DriveNames.MIME_FOLDER
    }

    val nodes = LinkedHashMap<String, Node>()
    val calls = CopyOnWriteArrayList<String>()
    /** Thrown, one per call, before anything else happens. */
    val failNext = ArrayDeque<DriveException>()
    /** Uploads converting to this type are refused (Drive can't convert). */
    var refuseConversion = false
    var email: String? = "me@example.com"
    private var counter = 0

    private fun step(call: String) {
        calls += call
        failNext.removeFirstOrNull()?.let { throw it }
    }

    private fun live(id: String): Node? = nodes[id]?.takeIf { !it.trashed && it.parents.all { p -> p == ROOT || live(p) != null } }

    override suspend fun accountEmail(): String? {
        step("about")
        return email
    }

    override suspend fun get(id: String): DriveItem? {
        step("get")
        return live(id)?.toItem()
    }

    override suspend fun findByProperty(key: String, value: String, parentId: String?): List<DriveItem> {
        step("find:$key")
        return nodes.values.filter { live(it.id) != null && it.properties[key] == value && (parentId == null || parentId in it.parents) }.map { it.toItem() }
    }

    override suspend fun findFolder(parentId: String, name: String): DriveItem? {
        step("findFolder:$name")
        return nodes.values.firstOrNull { live(it.id) != null && it.isFolder && it.name == name && parentId in it.parents }?.toItem()
    }

    override suspend fun children(parentId: String): List<DriveItem> {
        step("children")
        return nodes.values.filter { live(it.id) != null && parentId in it.parents }.map { it.toItem() }
    }

    override suspend fun createFolder(name: String, parentId: String?, properties: Map<String, String>): DriveItem {
        step("mkdir:$name")
        if (parentId != null && live(parentId) == null) throw DriveException.NotFound()
        val node = Node("id${++counter}", name, DriveNames.MIME_FOLDER, listOf(parentId ?: ROOT), properties.toMutableMap())
        nodes[node.id] = node
        return node.toItem()
    }

    override suspend fun update(id: String, name: String, addParent: String?, removeParents: List<String>): DriveItem {
        step("update:$name")
        val node = live(id) ?: throw DriveException.NotFound()
        node.name = name
        if (addParent != null) node.parents = node.parents - removeParents.toSet() + addParent
        return node.toItem()
    }

    override suspend fun upload(upload: DriveUpload): DriveItem {
        step("upload:${upload.name}")
        val bytes = when (val c = upload.content) {
            is DriveContent.Bytes -> c.bytes
            is DriveContent.FromFile -> c.file.readBytes()
        }
        val existing = upload.existingId
        if (existing != null) {
            val node = live(existing) ?: throw DriveException.NotFound()
            node.name = upload.name
            node.content = bytes
            node.uploadedAs = upload.mimeType
            node.properties.putAll(upload.properties)
            node.versions++
            return node.toItem()
        }
        if (live(upload.parentId) == null) throw DriveException.NotFound()
        if (upload.convertTo != null && refuseConversion) throw DriveException.Rejected(400, "badContent")
        val node = Node(
            "id${++counter}", upload.name, upload.convertTo ?: upload.mimeType, listOf(upload.parentId),
            upload.properties.toMutableMap(), bytes, uploadedAs = upload.mimeType,
        )
        nodes[node.id] = node
        return node.toItem()
    }

    /** "Inkwell/Economics/Term 1/Title/notes.pdf" for a live node. */
    fun path(id: String): String {
        val node = nodes.getValue(id)
        val parent = node.parents.first()
        return if (parent == ROOT) node.name else path(parent) + "/" + node.name
    }

    /** Paths of every live file (not folders), sorted. */
    fun files(): List<String> = nodes.values.filter { !it.isFolder && live(it.id) != null }.map { path(it.id) }.sorted()

    fun node(path: String): Node? = nodes.values.firstOrNull { live(it.id) != null && path(it.id) == path }

    /** Calls other than lookups, for asserting what was written. */
    fun writes(): List<String> = calls.filter { it.startsWith("mkdir:") || it.startsWith("upload:") || it.startsWith("update:") }

    companion object {
        const val ROOT = "root"
    }
}

fun doc(
    id: String = "0b6f2c1e-8a4d-4c1b-9f7e-1d2c3b4a5f60",
    title: String = "20261015_Financial Markets",
    folderId: String? = null,
    modifiedAt: Long = 1_760_500_000_000,
    lastSyncedAt: Long = 0,
    kind: DocKind = DocKind.NOTEBOOK,
) = DocumentMeta(
    id = id, title = title, kind = kind, folderId = folderId, createdAt = 1_760_486_400_000, modifiedAt = modifiedAt,
    pageCount = 12, tags = listOf("finance", "lecture"), lastSyncedAt = lastSyncedAt,
)

fun recording(
    id: String = "rec-1",
    docId: String = "0b6f2c1e-8a4d-4c1b-9f7e-1d2c3b4a5f60",
    title: String = "Lecture 3",
    startedAt: Long = 1_760_490_000_000,
    status: RecordingStatus = RecordingStatus.COMPLETE,
    transcriptStatus: TranscriptStatus = TranscriptStatus.DONE,
) = Recording(
    id = id, documentId = docId, title = title, segmentFiles = listOf("/data/seg_000.m4a"), segmentDurationsMs = listOf(65_000),
    durationMs = 65_000, startedAt = startedAt, endedAt = startedAt + 65_000, status = status, transcriptStatus = transcriptStatus,
)

/** Builds an engine over fakes. */
class EngineHarness(
    val tmp: File,
    var prefs: DestinationPrefs = DestinationPrefs(),
    val drive: FakeDrive = FakeDrive(),
    val folderRoot: File? = null,
    val folderRename: Boolean = true,
) {
    val dao = FakeSyncQueueDao()
    val source = FakeSource(File(tmp, "exports").apply { mkdirs() })
    val network = FakeNetwork()
    val state = InMemorySyncState()
    var revoked = 0
    /** Accounts the engine asked a Drive client for. */
    val driveAccounts = CopyOnWriteArrayList<String>()
    var folderAccess: FolderAccess = FolderAccess { _ ->
        JavaFileNode(folderRoot ?: throw FolderRevokedException(), folderRename)
    }
    var now = 1_760_600_000_000

    val engine = SyncEngine(
        queue = { dao },
        source = source,
        prefs = { prefs },
        driveClient = { account -> driveAccounts += account; drive },
        folders = FolderAccess { uri -> folderAccess.root(uri) },
        network = network,
        state = state,
        onFolderRevoked = { revoked++; prefs = prefs.withoutFolder() },
        clock = { now },
        io = Dispatchers.Unconfined,
    )
}
