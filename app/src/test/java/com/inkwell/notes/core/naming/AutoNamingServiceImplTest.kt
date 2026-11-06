package com.inkwell.notes.core.naming

import android.graphics.Bitmap
import com.inkwell.notes.ai.AiService
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiEvent
import com.inkwell.notes.core.model.AiModelInfo
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiResult
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.AiUsage
import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.core.model.RecognizedBlock
import com.inkwell.notes.core.model.SearchHit
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.Transcript
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.storage.RepositoryTestHarness
import com.inkwell.notes.ocr.IndexedPassage
import com.inkwell.notes.ocr.SearchIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The background naming job end to end: real repository and settings, in-memory database,
 * fake search index (what the indexer recognised) and fake AI. The service uses the wall clock
 * for "now", so documents are created with a creation time relative to it.
 */
class AutoNamingServiceImplTest {
    private lateinit var h: RepositoryTestHarness
    private lateinit var graph: AppGraph
    private lateinit var service: AutoNamingServiceImpl
    private val search = FakeSearchIndex()
    private val ai = FakeAiService()

    private val lecture = """
        Lecture 4 - central banking
        Central banks set monetary policy to keep inflation close to target.
        When inflation rises, the central bank raises the policy rate and bond yields usually rise too.
        Quantitative easing lowers bond yields and loosens monetary policy.
        Tightening monetary policy tends to flatten the yield curve.
        Credibility of monetary policy anchors inflation expectations.
    """.trimIndent()

    @Before fun setUp() {
        h = RepositoryTestHarness()
        graph = AppGraph(h.context)
        graph.inject("db", h.db)
        graph.inject("files", h.files)
        graph.inject("pageStore", h.pageStore)
        graph.inject("settings", h.settings)
        graph.inject("library", h.repo)
        graph.inject("search", search)
        graph.inject("ai", ai)
        service = AutoNamingServiceImpl(graph)
    }

    @After fun tearDown() { h.close() }

    private fun <T> AppGraph.inject(name: String, value: T) {
        val field = AppGraph::class.java.getDeclaredField("$name\$delegate")
        field.isAccessible = true
        field.set(this, lazyOf(value))
    }

    private val hour = 60L * 60 * 1000

    /** A notebook created [hoursAgo] hours ago (wall clock) with [text] recognised on its first page. */
    private suspend fun notebook(hoursAgo: Double, text: String? = lecture): DocumentMeta {
        h.now = System.currentTimeMillis() - (hoursAgo * hour).toLong()
        val doc = h.repo.createDocument(DocKind.NOTEBOOK)
        if (text != null) search.texts[doc.id] = text
        return doc
    }

    private suspend fun status(id: String) = h.repo.getDocument(id)!!.autoNameStatus

    /** Background naming may only send content once "Confirm before sending content to AI" is off. */
    private suspend fun allowBackgroundAi() {
        h.settings.update { it.copy(privacy = it.privacy.copy(confirmAiTransmission = false)) }
    }
    private suspend fun title(id: String) = h.repo.getDocument(id)!!.title

    @Test fun nothingIsRenamedBeforeTheWindowAndEverythingDueIsRenamedAfter() = runTest {
        val young = notebook(hoursAgo = 23.5)
        val due = notebook(hoursAgo = 24.5)
        val prefix = DateNaming.datePrefix(due.createdAt)
        assertEquals(1, service.runDue())
        assertEquals("${prefix}_Monetary Policy", title(due.id))
        assertEquals(AutoNameStatus.DONE, status(due.id))
        assertTrue(DateNaming.isUntitled(title(young.id)))
        assertEquals(AutoNameStatus.PENDING, status(young.id))
        // Running again renames nothing: DONE is final.
        assertEquals(0, service.runDue())
        assertEquals(0, ai.calls)
    }

    @Test fun theConfiguredModelIsUsedAndItsAnswerCleaned() = runTest {
        allowBackgroundAi()
        ai.available = true
        ai.reply = { "Topic: \"Central Bank Policy.\"" }
        val due = notebook(hoursAgo = 30.0)
        assertEquals(1, service.runDue())
        assertEquals("${DateNaming.datePrefix(due.createdAt)}_Central Bank Policy", title(due.id))
        assertEquals(1, ai.calls)
        assertEquals(AiTask.NAMING, ai.lastRequest!!.task)
        assertEquals(due.id, ai.lastRequest!!.documentId)
        assertTrue(ai.lastRequest!!.messages.single().text().contains("monetary policy"))
    }

    @Test fun offlineModelQueuesTheDocumentAndLocalInferenceTakesOverAfterThreeFailures() = runTest {
        allowBackgroundAi()
        ai.available = true
        ai.reply = { throw IOException("offline") }
        val due = notebook(hoursAgo = 30.0)
        assertEquals(0, service.runDue())
        assertEquals(AutoNameStatus.QUEUED, status(due.id))
        assertTrue(DateNaming.isUntitled(title(due.id)))
        assertEquals(0, service.runDue())
        assertEquals(0, service.runDue())
        assertEquals(AutoNameStatus.QUEUED, status(due.id))
        assertEquals(3, ai.calls)
        // Fourth run: the model is skipped and the on-device extractor names the document.
        assertEquals(1, service.runDue())
        assertEquals(3, ai.calls)
        assertEquals(AutoNameStatus.DONE, status(due.id))
        assertEquals("${DateNaming.datePrefix(due.createdAt)}_Monetary Policy", title(due.id))
    }

    @Test fun insufficientContentIsRecheckedAtMostSixTimesTwelveHoursApart() = runTest {
        val due = notebook(hoursAgo = 48.0, text = "Buy milk")
        assertEquals(0, service.runDue())
        assertEquals(AutoNameStatus.INSUFFICIENT, status(due.id))
        assertEquals(1, h.repo.getDocument(due.id)!!.autoNameAttempts)
        // Immediately again: not re-analysed (12h spacing).
        assertEquals(0, service.runDue())
        assertEquals(1, h.repo.getDocument(due.id)!!.autoNameAttempts)
        // Pretend 13 hours passed since the last check.
        val state = NamingStateStore(File(File(h.context.filesDir, "naming"), "state.json"))
        for (attempt in 2..AutoNamingRules.MAX_INSUFFICIENT_ATTEMPTS) {
            state.update(due.id) { it.copy(lastCheckAt = System.currentTimeMillis() - 13 * hour) }
            assertEquals(0, service.runDue())
            assertEquals(attempt, h.repo.getDocument(due.id)!!.autoNameAttempts)
        }
        state.update(due.id) { it.copy(lastCheckAt = System.currentTimeMillis() - 100 * hour) }
        assertEquals(0, service.runDue())
        assertEquals("bounded: no seventh attempt", AutoNamingRules.MAX_INSUFFICIENT_ATTEMPTS, h.repo.getDocument(due.id)!!.autoNameAttempts)
        assertTrue(DateNaming.isUntitled(title(due.id)))
        // Content arrives later and the user asks explicitly: renamed at once.
        search.texts[due.id] = lecture
        assertEquals("${DateNaming.datePrefix(due.createdAt)}_Monetary Policy", service.renameNow(due.id))
    }

    @Test fun manualImportedDisabledAndDoneDocumentsAreNeverTouched() = runTest {
        ai.available = true
        ai.reply = { "Physics" }
        val manual = notebook(hoursAgo = 50.0)
        h.repo.renameDocument(manual.id, "My Notes")
        val imported = h.repo.createDocument(DocKind.PDF, originalName = "slides.pdf").also { search.texts[it.id] = lecture }
        val done = notebook(hoursAgo = 50.0)
        h.repo.applyAutoName(done.id, "Old Topic")
        h.settings.update { it.copy(naming = it.naming.copy(autoNameEnabled = false)) }
        val disabled = notebook(hoursAgo = 50.0)
        h.settings.update { it.copy(naming = it.naming.copy(autoNameEnabled = true)) }
        val trashed = notebook(hoursAgo = 50.0)
        h.repo.trashDocument(trashed.id)

        assertEquals(0, service.runDue())
        assertEquals(0, ai.calls)
        assertEquals("My Notes", title(manual.id))
        assertEquals("slides", title(imported.id))
        assertEquals("${DateNaming.datePrefix(done.createdAt)}_Old Topic", title(done.id))
        assertTrue(DateNaming.isUntitled(title(disabled.id)))
        assertTrue(DateNaming.isUntitled(title(trashed.id)))
    }

    @Test fun aRenameByTheUserDuringAnalysisWins() = runTest {
        allowBackgroundAi()
        ai.available = true
        val due = notebook(hoursAgo = 30.0)
        ai.reply = {
            // While the model is "thinking", the user names the document.
            h.repo.renameDocument(due.id, "Mine")
            "Physics"
        }
        assertEquals(0, service.runDue())
        assertEquals("Mine", title(due.id))
        assertEquals(AutoNameStatus.MANUAL, status(due.id))
    }

    @Test fun backgroundNamingSendsNothingWhileConfirmationIsOn() = runTest {
        // Defaults: AI naming on, "Confirm before sending content to AI" on. A background run cannot ask.
        ai.available = true
        val due = notebook(hoursAgo = 30.0)
        assertEquals(1, service.runDue())
        assertEquals(0, ai.calls)
        assertEquals("${DateNaming.datePrefix(due.createdAt)}_Monetary Policy", title(due.id))
        // Asking for a name is an explicit action and may use the model.
        val other = notebook(hoursAgo = 1.0)
        assertEquals("${DateNaming.datePrefix(other.createdAt)}_Physics", service.renameNow(other.id))
        assertEquals(1, ai.calls)
    }

    @Test fun renameNowWorksForImportedDocumentsAndSuggestDoesNotRename() = runTest {
        val imported = h.repo.createDocument(DocKind.PDF, originalName = "slides.pdf")
        search.texts[imported.id] = lecture
        assertEquals("Monetary Policy", service.suggestTopic(imported.id))
        assertEquals("slides", title(imported.id))
        assertEquals(AutoNameStatus.IMPORTED, status(imported.id))
        val renamed = service.renameNow(imported.id)
        assertEquals("${DateNaming.datePrefix(imported.createdAt)}_Monetary Policy", renamed)
        assertEquals(AutoNameStatus.DONE, status(imported.id))
        assertNull(service.renameNow("ghost"))
        assertNull(service.suggestTopic(h.repo.createDocument(DocKind.NOTEBOOK).id))
    }

    @Test fun renameNowFallsBackToLocalInferenceWhenTheModelIsOffline() = runTest {
        ai.available = true
        ai.reply = { throw IOException("offline") }
        val doc = notebook(hoursAgo = 1.0)
        assertEquals("${DateNaming.datePrefix(doc.createdAt)}_Monetary Policy", service.renameNow(doc.id))
        assertEquals(1, ai.calls)
    }

    @Test fun disabledSettingStopsTheJobEntirely() = runTest {
        val due = notebook(hoursAgo = 30.0)
        h.settings.update { it.copy(naming = it.naming.copy(autoNameEnabled = false)) }
        assertEquals(0, service.runDue())
        assertEquals(AutoNameStatus.PENDING, status(due.id))
    }

    @Test fun customDelayIsHonoured() = runTest {
        h.settings.update { it.copy(naming = it.naming.copy(delayHours = 2)) }
        val due = notebook(hoursAgo = 2.5)
        val young = notebook(hoursAgo = 1.5)
        assertEquals(1, service.runDue())
        assertEquals(AutoNameStatus.DONE, status(due.id))
        assertEquals(AutoNameStatus.PENDING, status(young.id))
    }

    @Test fun textBoxesOnPagesCountAsContentWhenNothingIsIndexed() = runTest {
        val doc = notebook(hoursAgo = 30.0, text = null)
        val page = h.repo.getPages(doc.id).single()
        h.pageStore.save(
            doc.id, page.id,
            com.inkwell.notes.core.model.PageContent(
                texts = listOf(com.inkwell.notes.core.model.TextItem("t", 0f, 0f, 300f, 400f, lecture, fontSizePt = 14f)),
            ),
        )
        assertEquals(1, service.runDue())
        assertEquals("${DateNaming.datePrefix(doc.createdAt)}_Monetary Policy", title(doc.id))
    }

    // ---- fakes

    class FakeSearchIndex : SearchIndex {
        val texts = HashMap<String, String>()
        override suspend fun search(query: String, limit: Int, semantic: Boolean): List<SearchHit> = emptyList()
        override suspend fun indexDocumentMeta(doc: DocumentMeta) {}
        override suspend fun replacePageText(docId: String, pageId: String, source: TextSource, blocks: List<RecognizedBlock>, engine: String) {}
        override suspend fun replaceTranscript(docId: String, recordingId: String, segments: List<TranscriptSegment>, engine: String) {}
        override suspend fun removeDocument(docId: String) {}
        override suspend fun documentText(docId: String): List<IndexedPassage> =
            texts[docId]?.let { listOf(IndexedPassage(docId, "page-1", 0, null, null, TextSource.HANDWRITING, it)) } ?: emptyList()
        override suspend fun retrieve(docId: String?, query: String, maxChars: Int): List<IndexedPassage> = emptyList()
    }

    class FakeAiService : AiService {
        var available = false
        var calls = 0
        var lastRequest: AiRequest? = null
        var reply: suspend (AiRequest) -> String = { "Physics" }
        private val model = ModelRef(AiProviderId.OPENAI, "fake-model")
        override fun isAvailable(task: AiTask): Boolean = available
        override fun modelFor(task: AiTask): ModelRef? = if (available) model else null
        override suspend fun models(provider: AiProviderId, refresh: Boolean): List<AiModelInfo> = emptyList()
        override suspend fun modelsWith(capability: AiCapability): List<AiModelInfo> = emptyList()
        override fun modelInfo(ref: ModelRef): AiModelInfo? = null
        override suspend fun validateKey(provider: AiProviderId, apiKey: String, baseUrl: String?): Result<String> = Result.success("ok")
        override fun stream(request: AiRequest): Flow<AiEvent> = emptyFlow()
        override suspend fun complete(request: AiRequest): AiResult {
            calls++
            lastRequest = request
            return AiResult(reply(request), AiUsage(0, 0, null), model)
        }
        override fun estimateTokens(request: AiRequest): Int = 0
        override fun estimateCostUsd(request: AiRequest, expectedOutputTokens: Int): Double? = null
        override suspend fun transcribe(recordingId: String, segments: List<File>, segmentDurationsMs: List<Long>, language: String?): Transcript? = null
        override suspend fun embed(texts: List<String>): List<FloatArray>? = null
        override suspend fun visionTranscribe(image: Bitmap, hint: String?): AiResult? = null
    }
}
