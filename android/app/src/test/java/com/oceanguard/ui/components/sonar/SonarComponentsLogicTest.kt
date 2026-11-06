package com.oceanguard.ai.ui.components.sonar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SonarComponentsLogicTest {

    private val tau = (2.0 * Math.PI).toFloat()

    @Test fun echoIsBrightestWhenTheLinePassesAndThenFades() {
        val justPassed = SonarRadarMath.echoLife(1.01f, 1f, 1f)
        val later = SonarRadarMath.echoLife(2f, 1f, 1f)
        assertTrue(justPassed > 0.99f)
        assertEquals(0.68f, later, 1e-3f)
    }

    @Test fun echoAheadOfTheLineIsDark() {
        assertEquals(0f, SonarRadarMath.echoLife(0.9f, 1f, 1f), 0f)
    }

    @Test fun angleBehindWrapsAroundTheCircle() {
        assertEquals(0.5f, SonarRadarMath.angleBehind(0.2f, tau - 0.3f), 1e-4f)
        assertEquals(1f, SonarRadarMath.angleBehind(-0.5f, -1.5f), 1e-4f)
    }

    @Test fun radarPeriodFollowsTheSpeed() {
        assertEquals(5711, SonarRadarMath.periodMs(1.1f))
        assertTrue(SonarRadarMath.periodMs(0.55f) > SonarRadarMath.periodMs(0.8f))
        assertEquals(0, SonarRadarMath.periodMs(0f))
    }

    private class Recorder(val scope: CoroutineScope) {
        val window = CompletableDeferred<Boolean>()
        val committed = mutableListOf<String>()
        val deletes = UndoableDeletes<String>(
            scope = scope,
            askUndo = { window.await() },
            onCommit = { committed += it },
        )
    }

    private fun withRecorder(block: suspend (Recorder, Job) -> Unit) = runBlocking {
        val job = Job()
        val recorder = Recorder(CoroutineScope(job + Dispatchers.Unconfined))
        block(recorder, job)
    }

    @Test fun rowHidesAtOnceAndDeletesWhenTheWindowCloses() = withRecorder { r, _ ->
        r.deletes.request("a")
        assertTrue(r.deletes.isPending("a"))
        assertTrue(r.committed.isEmpty())
        r.window.complete(false)
        yield()
        assertEquals(listOf("a"), r.committed)
        assertFalse(r.deletes.isPending("a"))
    }

    @Test fun undoKeepsTheData() = withRecorder { r, _ ->
        r.deletes.request("a")
        r.window.complete(true)
        yield()
        assertTrue(r.committed.isEmpty())
        assertFalse(r.deletes.isPending("a"))
    }

    @Test fun leavingTheScreenStillDeletes() = withRecorder { r, job ->
        r.deletes.request("a")
        job.cancel()
        yield()
        assertEquals(listOf("a"), r.committed)
    }

    @Test fun repeatedRequestsDeleteOnce() = withRecorder { r, _ ->
        r.deletes.request("a")
        r.deletes.request("a")
        r.window.complete(false)
        yield()
        assertEquals(listOf("a"), r.committed)
    }
}
