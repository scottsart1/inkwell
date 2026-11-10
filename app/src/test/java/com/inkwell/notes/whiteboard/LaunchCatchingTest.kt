package com.inkwell.notes.whiteboard

import com.inkwell.notes.whiteboard.kit.launchCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchCatchingTest {
    @Test
    fun aFailingActionIsReportedAndTheScopeKeepsWorking() = runTest {
        // Like rememberCoroutineScope: a plain (non-supervisor) Job.
        val screen = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val errors = mutableListOf<Throwable>()
        screen.launchCatching({ errors += it }) { error("board read failed") }
        advanceUntilIdle()
        var ranAfter = false
        screen.launchCatching({ errors += it }) { ranAfter = true }
        advanceUntilIdle()
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalStateException)
        assertTrue("the next action still runs", ranAfter)
    }

    @Test
    fun cancellationIsNotAnError() = runTest {
        val screen = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val errors = mutableListOf<Throwable>()
        val job = screen.launchCatching({ errors += it }) { awaitCancellation() }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertTrue(errors.isEmpty())
    }
}
