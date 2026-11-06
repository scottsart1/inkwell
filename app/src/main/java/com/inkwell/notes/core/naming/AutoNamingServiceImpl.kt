package com.inkwell.notes.core.naming

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.DocumentMeta
import com.inkwell.notes.core.model.NamingPrefs
import com.inkwell.notes.core.storage.LibraryRepositoryImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Automatic "YYYYMMDD_Topic" naming.
 *
 * Scheduling: [schedule] (called at app start) books the hourly [AutoNamingWorker] and a
 * precise one-off check for the next deadline. The library repository does not schedule
 * anything itself: new documents are PENDING rows the worker finds on its own, so storage
 * stays independent of WorkManager and nothing is lost if the app is killed after creating one.
 *
 * Each due document ([AutoNamingRules.isEligible]) is analysed with [NamingContentCollector] and
 * [TopicInference]. A topic is applied with [com.inkwell.notes.core.storage.LibraryRepository.applyAutoName]
 * only if the document is still waiting for a name at that moment, so a rename by the user while
 * the analysis runs always wins.
 */
class AutoNamingServiceImpl(private val graph: AppGraph) : AutoNamingService {
    private val runMutex = Mutex()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val inference = TopicInference()
    private val collector by lazy { NamingContentCollector(graph) }
    private val state by lazy { NamingStateStore(File(File(graph.context.filesDir, "naming"), "state.json")) }

    override fun schedule() {
        try {
            AutoNamingWorker.schedulePeriodic(graph.context)
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule background naming: ${e.javaClass.simpleName}")
        }
        graph.appScope.launch {
            try {
                scheduleNextPreciseCheck()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "Could not book the next naming check: ${e.javaClass.simpleName}")
            }
        }
    }

    override suspend fun runDue(): Int {
        val prefs = graph.settings.current.naming
        if (!prefs.autoNameEnabled) return 0
        // Another run (periodic and precise overlapping) is already doing the work.
        if (!runMutex.tryLock()) return 0
        try {
            val candidates = graph.db.documents()
                .withAutoNameStatus(AutoNamingRules.AUTOMATIC.toList())
                .map { it.toModel() }
            state.retainOnly(candidates.mapTo(HashSet()) { it.id })
            val checks = state.all()
            var renamed = 0
            var processed = 0
            for (doc in candidates.sortedBy { it.createdAt }) {
                // Settings may change while a long run is in progress; a bounded batch keeps each run
                // well inside WorkManager's execution window (the rest follows on the next run).
                if (!graph.settings.current.naming.autoNameEnabled || processed >= MAX_DOCS_PER_RUN) break
                val now = System.currentTimeMillis()
                val lastCheck = checks[doc.id]?.lastCheckAt?.takeIf { it > 0 }
                if (!AutoNamingRules.isEligible(doc.autoNameStatus, doc.createdAt, doc.autoNameAttempts, lastCheck, now, prefs)) continue
                if (!inFlight.add(doc.id)) continue
                processed++
                try {
                    if (nameAutomatically(doc, checks[doc.id] ?: NamingDocState(), prefs)) renamed++
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    Log.w(TAG, "Naming failed for ${doc.id}: ${e.javaClass.simpleName}")
                } finally {
                    inFlight.remove(doc.id)
                }
            }
            return renamed
        } finally {
            runMutex.unlock()
        }
    }

    private suspend fun nameAutomatically(doc: DocumentMeta, docState: NamingDocState, prefs: NamingPrefs): Boolean {
        val content = collector.collect(doc)
        val outcome = inference.infer(content, aiNamer(doc.id, prefs, background = true), docState.aiFailures, userRequested = false)
        val now = System.currentTimeMillis()
        if (outcome is TopicOutcome.Found) {
            val title = applyIfStillWaiting(doc.id, outcome.topic) ?: return false
            state.update(doc.id) { null }
            return title.isNotEmpty()
        }
        val update = AutoNamingRules.afterUnsuccessful(outcome, doc.autoNameAttempts, docState.aiFailures) ?: return false
        graph.db.documents().updateAutoNameState(doc.id, update.status, update.attempts, AutoNamingRules.AUTOMATIC.toList())
        state.update(doc.id) { NamingDocState(lastCheckAt = now, aiFailures = update.aiFailures) }
        return false
    }

    /** Applies [topic] unless the user renamed or trashed the document meanwhile. */
    private suspend fun applyIfStillWaiting(docId: String, topic: String): String? {
        val library = graph.library
        if (library is LibraryRepositoryImpl) return library.applyAutoNameIfWaiting(docId, topic)
        // Any other implementation: check, then apply through the contract.
        val current = library.getDocument(docId) ?: return null
        if (current.autoNameStatus !in AutoNamingRules.AUTOMATIC || current.trashedAt != null) return null
        return library.applyAutoName(docId, topic)
    }

    override suspend fun renameNow(docId: String): String? = userRequest("rename") { renameNowInternal(docId) }

    private suspend fun renameNowInternal(docId: String): String? {
        val doc = graph.library.getDocument(docId) ?: return null
        val prefs = graph.settings.current.naming
        // Keeps a background run from starting on it meanwhile. If one is already analysing it,
        // the user's request still proceeds: the background result is only applied while the
        // document is waiting for a name, which it no longer is once this sets DONE.
        val claimed = inFlight.add(docId)
        try {
            val outcome = inference.infer(collector.collect(doc), aiNamer(docId, prefs), aiFailures = 0, userRequested = true)
            if (outcome !is TopicOutcome.Found) return null
            val title = graph.library.applyAutoName(docId, outcome.topic)
            state.update(docId) { null }
            return title
        } finally {
            if (claimed) inFlight.remove(docId)
        }
    }

    override suspend fun suggestTopic(docId: String): String? = userRequest("suggestion") {
        val doc = graph.library.getDocument(docId) ?: return@userRequest null
        val prefs = graph.settings.current.naming
        val outcome = inference.infer(collector.collect(doc), aiNamer(docId, prefs), aiFailures = 0, userRequested = true)
        (outcome as? TopicOutcome.Found)?.topic
    }

    /** User-facing calls never throw into the UI: a failure reads as "no name found". */
    private suspend fun userRequest(what: String, block: suspend () -> String?): String? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.w(TAG, "Naming $what failed: ${e.javaClass.simpleName}")
        null
    }

    /** Books a one-off check for the next naming deadline if it falls before the next hourly run. */
    suspend fun scheduleNextPreciseCheck() {
        val prefs = graph.settings.current.naming
        if (!prefs.autoNameEnabled) {
            AutoNamingWorker.cancelPrecise(graph.context)
            return
        }
        val checks = state.all()
        val next = graph.db.documents()
            .withAutoNameStatus(AutoNamingRules.AUTOMATIC.toList())
            .mapNotNull { d ->
                AutoNamingRules.nextCheckAt(d.autoNameStatus, d.createdAt, d.autoNameAttempts, checks[d.id]?.lastCheckAt?.takeIf { it > 0 }, prefs)
            }
            .minOrNull() ?: return
        val delay = (next - System.currentTimeMillis()).coerceAtLeast(0)
        if (delay <= PRECISE_HORIZON_MS) AutoNamingWorker.schedulePrecise(graph.context, delay + PRECISE_SLACK_MS)
    }

    /**
     * The AI caller for naming, or null when AI is off, unavailable, or disabled for naming. A
     * [background] run cannot ask first, so it stays on-device keywords while "Confirm before
     * sending content to AI" is on; user-requested naming is itself the explicit action.
     */
    private fun aiNamer(docId: String, prefs: NamingPrefs, background: Boolean = false): TopicInference.AiNamer? {
        if (!prefs.useAi) return null
        if (background && graph.settings.current.privacy.confirmAiTransmission) return null
        val available = try {
            graph.ai.isAvailable(AiTask.NAMING)
        } catch (e: Exception) {
            false
        }
        if (!available) return null
        return TopicInference.AiNamer { system, user ->
            graph.ai.complete(
                AiRequest(
                    task = AiTask.NAMING,
                    messages = listOf(AiMessage(AiRole.USER, user)),
                    system = system,
                    maxOutputTokens = NamingPrompt.MAX_OUTPUT_TOKENS,
                    temperature = 0.2f,
                    documentId = docId,
                ),
            ).text
        }
    }

    private companion object {
        const val TAG = "AutoNaming"
        const val MAX_DOCS_PER_RUN = 60
        /** Precise checks are only booked for deadlines before the next periodic run (1 h + flex). */
        const val PRECISE_HORIZON_MS = 75L * 60 * 1000
        /** Small margin so the check runs just after the deadline, not just before it. */
        const val PRECISE_SLACK_MS = 30L * 1000
    }
}
