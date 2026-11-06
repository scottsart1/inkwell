package com.inkwell.notes.core.storage

import android.content.ContextWrapper
import com.inkwell.notes.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files

/** Records what the library told the rest of the app. */
class RecordingHooks : LibraryHooks {
    val metaChanged = ArrayList<String>()
    val renamed = ArrayList<String>()
    /** Documents moved to another folder or below a renamed/moved folder (also in [metaChanged]). */
    val moved = ArrayList<String>()
    val needIndexing = ArrayList<Pair<String, List<String>>>()
    val coverChanged = ArrayList<String>()
    val deleted = ArrayList<String>()
    /** Recording ids whose voice data the speaker service was told to remove, in call order. */
    val recordingsDeleted = ArrayList<String>()
    var pdfPng: ByteArray? = null
    val pdfRenders = ArrayList<Triple<String, Int, Pair<Int, Int>>>()

    override fun documentMetaChanged(docId: String) { metaChanged += docId }
    override fun documentRenamed(docId: String) { renamed += docId }
    override fun documentMoved(docId: String) {
        moved += docId
        metaChanged += docId
    }
    override fun pagesNeedIndexing(docId: String, pageIds: List<String>) { needIndexing += docId to pageIds }
    override fun coverChanged(docId: String) { coverChanged += docId }
    override fun documentDeleted(docId: String) { deleted += docId }
    override fun recordingsDeleted(docId: String, recordingIds: List<String>) { recordingsDeleted += recordingIds }
    override suspend fun renderPdfPagePng(docId: String, pdfPageIndex: Int, widthPx: Int, heightPx: Int): ByteArray? {
        pdfRenders += Triple(docId, pdfPageIndex, widthPx to heightPx)
        return pdfPng
    }
}

/**
 * A complete repository on a temp directory: fake Room, real files, real settings (DataStore on
 * the JVM), recording hooks and a clock the test advances. Call [close] in @After.
 */
class RepositoryTestHarness {
    val tmp: File = Files.createTempDirectory("inkwell-repo").toFile()
    val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = File(tmp, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(tmp, "cache").apply { mkdirs() }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db = FakeAppDatabase()
    val files = FileStore(context)
    val pageStore = PageContentStore(files)
    val settings = SettingsRepository(context, scope)
    val hooks = RecordingHooks()
    var now: Long = 1_760_000_000_000L // 2025-10-09T08:53:20Z
    val repo = LibraryRepositoryImpl(db, files, pageStore, settings, hooks) { now }

    fun advance(ms: Long) { now += ms }

    fun close() {
        scope.cancel()
        tmp.deleteRecursively()
    }
}
