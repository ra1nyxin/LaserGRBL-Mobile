package com.x.lasergrbl_mobile.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GcodeStreamerTest {
    @Test
    fun sendsNextLineAfterOk() = runTest {
        val transport = FakeTransport()
        val streamer = GcodeStreamer(this, transport)
        val events = mutableListOf<StreamEvent>()
        val collectJob = launch { streamer.events.collect { events.add(it) } }

        streamer.start(GcodeParser.parse("G0 X0\nG1 X1"))
        waitFor { transport.lines.size == 1 }
        assertEquals("G0 X0", transport.lines[0])

        streamer.enqueueResponse(Response.Ok)
        waitFor { transport.lines.size == 2 }
        assertEquals("G1 X1", transport.lines[1])

        streamer.enqueueResponse(Response.Ok)
        waitFor { streamer.progress.value.acknowledgedLines == 2 }
        collectJob.cancel()
    }

    @Test
    fun clearsStaleResponsesBeforeStartingNewJob() = runTest {
        val transport = FakeTransport()
        val streamer = GcodeStreamer(this, transport)

        streamer.enqueueResponse(Response.Ok)
        streamer.start(GcodeParser.parse("G0 X0"))
        waitFor { transport.lines.size == 1 }

        assertEquals(0, streamer.progress.value.acknowledgedLines)
        streamer.enqueueResponse(Response.Ok)
        waitFor { streamer.progress.value.acknowledgedLines == 1 }
    }

    @Test
    fun writeFailureStopsJobAndEmitsError() = runTest {
        val transport = FakeTransport(failOnWrite = true)
        val streamer = GcodeStreamer(this, transport)

        streamer.start(GcodeParser.parse("G0 X0"))
        waitFor { streamer.progress.value.errorCount == 1 }

        assertEquals(false, streamer.progress.value.running)
        assertEquals(1, streamer.progress.value.errorCount)
    }

    private suspend fun waitFor(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            delay(10)
        }
        error("condition was not met")
    }

    private class FakeTransport(
        private val failOnWrite: Boolean = false,
    ) : GrblTransport {
        val lines = mutableListOf<String>()
        override suspend fun writeLine(line: String) {
            if (failOnWrite) error("port closed")
            lines += line
        }

        override suspend fun writeRealtime(command: String) = Unit
    }
}
