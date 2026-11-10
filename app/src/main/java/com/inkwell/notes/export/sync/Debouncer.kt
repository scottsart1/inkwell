package com.inkwell.notes.export.sync

import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.core.model.OutputTarget
import com.inkwell.notes.core.storage.db.SyncQueueEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs an action [delayMs] after the last [submit] for a key ("after edits settle"). A new
 * submit for the same key restarts its timer; keys are independent.
 */
class Debouncer(private val scope: CoroutineScope, private val delayMs: Long) {
    private val jobs = HashMap<String, Job>()

    fun submit(key: String, action: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            synchronized(jobs) { if (jobs[key] === coroutineContext[Job]) jobs.remove(key) }
            action()
        }
        synchronized(jobs) { jobs.put(key, job) }?.cancel()
        job.start()
    }

    fun cancel(key: String) {
        synchronized(jobs) { jobs.remove(key) }?.cancel()
    }

    val pendingKeys: Set<String> get() = synchronized(jobs) { jobs.keys.toSet() }
}

/** Which background runs the waiting [items] need under [prefs]. */
fun syncNeeds(items: List<SyncQueueEntity>, prefs: DestinationPrefs): SyncNeeds {
    var local = false
    var network = false
    var unmetered = false
    for (item in items) {
        when (item.target) {
            OutputTarget.FOLDER.name -> local = true
            OutputTarget.DRIVE.name, OutputTarget.VAULT.name ->
                if (item.kind == SyncKind.AUDIO.name && prefs.audioOnUnmeteredOnly) unmetered = true else network = true
        }
    }
    return SyncNeeds(local, network, unmetered)
}
