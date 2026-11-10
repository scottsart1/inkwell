package com.inkwell.notes.export.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.export.SyncManagerImpl
import java.util.concurrent.TimeUnit

/**
 * Background pass over the sync queue. The same worker runs under three unique names with their
 * own network constraint and [SyncScope], so folder copies never wait for a network, Drive and
 * vault uploads wait for any connection, and large audio waits for an unmetered one when the
 * user asked for that.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = AppGraph.get(applicationContext).sync as? SyncManagerImpl ?: return Result.success()
        val scope = inputData.getString(KEY_SCOPE)?.let { SyncScope.fromName(it) } ?: SyncScope.ALL
        return try {
            val report = manager.runQueue(scope)
            // After Google asked to slow down a delayed run is already scheduled: no quick retry.
            if (report.retryLater && !report.slowDown && runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("InkwellSync", "Sync run failed: ${e.javaClass.simpleName}")
            if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_SCOPE = "scope"
        private const val MAX_RUN_ATTEMPTS = 12
    }
}

/** Which background runs are needed for the items waiting in the queue. */
data class SyncNeeds(val local: Boolean = false, val network: Boolean = false, val unmetered: Boolean = false) {
    val any: Boolean get() = local || network || unmetered
}

/** Enqueues [SyncWorker] runs with WorkManager. Never throws. */
internal class SyncScheduler(private val context: Context) {
    /** Call off the main thread: it looks at the runs already there. */
    fun schedule(needs: SyncNeeds) {
        if (needs.local) runSoon(WORK_LOCAL, SyncScope.FOLDER, null)
        if (needs.network) runSoon(WORK_MAIN, SyncScope.ALL, NetworkType.CONNECTED)
        if (needs.unmetered) runSoon(WORK_AUDIO, SyncScope.NETWORK_AUDIO, NetworkType.UNMETERED)
    }

    private fun runSoon(name: String, scope: SyncScope, network: NetworkType?) {
        val states = try {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().map { it.state }
        } catch (e: Exception) {
            null
        }
        val policy = if (states == null) ExistingWorkPolicy.KEEP else policyFor(states) ?: return
        enqueue(name, scope, network, 0, policy)
    }

    /** Drops scheduled background runs (automatic sync switched off); queued items stay for "Sync now". */
    fun cancelAll() {
        try {
            val wm = WorkManager.getInstance(context)
            listOf(WORK_MAIN, WORK_AUDIO, WORK_LOCAL, WORK_LATER).forEach { wm.cancelUniqueWork(it) }
        } catch (e: Exception) {
            Log.w("InkwellSync", "Couldn't cancel sync: ${e.javaClass.simpleName}")
        }
    }

    /** One run after [delayMs] (e.g. when Google Drive asked Inkwell to slow down, or a vault lockout ends). */
    fun scheduleAfter(delayMs: Long) {
        enqueue(WORK_LATER, SyncScope.ALL, NetworkType.CONNECTED, delayMs.coerceAtLeast(1_000), ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(name: String, scope: SyncScope, network: NetworkType?, delayMs: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        try {
            val constraints = Constraints.Builder().apply { if (network != null) setRequiredNetworkType(network) }.build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(SyncWorker.KEY_SCOPE to scope.name))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        } catch (e: Exception) {
            Log.w("InkwellSync", "Couldn't schedule sync: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val WORK_MAIN = "inkwell-sync"
        const val WORK_AUDIO = "inkwell-sync-audio"
        const val WORK_LOCAL = "inkwell-sync-local"
        const val WORK_LATER = "inkwell-sync-later"
        const val TAG = "inkwell-sync"
        const val BACKOFF_SECONDS = 30L

        /**
         * How to ask for a run under a unique name that has work in [states]; null when nothing
         * is needed. A waiting run (retry backoff, which can grow to hours, or a missing network)
         * is replaced so new items go now. A run in progress may already have passed the new
         * items, so another run is queued behind it, once. Replacing a run in backoff does not
         * retry failed items early: each waits out its own backoff ([SyncEngine.retryDelayMs]).
         */
        internal fun policyFor(states: List<WorkInfo.State>): ExistingWorkPolicy? {
            val running = WorkInfo.State.RUNNING in states
            val queued = states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
            return when {
                running && queued -> null
                running -> ExistingWorkPolicy.APPEND_OR_REPLACE
                else -> ExistingWorkPolicy.REPLACE
            }
        }
    }
}
