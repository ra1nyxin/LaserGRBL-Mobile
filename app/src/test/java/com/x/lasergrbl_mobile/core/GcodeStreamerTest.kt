package com.x.lasergrbl_mobile.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private suspend fun waitFor(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            delay(10)
        }
        error("condition was not met")
    }

    private class FakeTransport : GrblTransport {
        val lines = mutableListOf<String>()
        override suspend fun writeLine(line: String) {
            lines += line
        }

        override suspend fun writeRealtime(command: String) = Unit
    }
}
