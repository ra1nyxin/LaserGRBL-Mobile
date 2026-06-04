package com.x.lasergrbl_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrblParserTest {
    @Test
    fun parseStatusLine() {
        val status = GrblParser.parseStatus("<Idle|MPos:1.000,2.000,-3.000|FS:1200,50|Bf:15,128>")

        requireNotNull(status)
        assertEquals(MachineState.Idle, status.state)
        assertEquals(1.0, status.machinePosition?.x ?: 0.0, 0.001)
        assertEquals(1200, status.feedRate)
        assertEquals(50, status.spindleSpeed)
        assertEquals(15, status.bufferAvailable)
    }

    @Test
    fun classifyResponses() {
        assertTrue(GrblParser.classifyResponse("ok") is Response.Ok)
        assertTrue(GrblParser.classifyResponse("error:20") is Response.Error)
        assertTrue(GrblParser.classifyResponse("ALARM:1") is Response.Alarm)
        assertTrue(GrblParser.classifyResponse("<Run|WPos:0,0,0>") is Response.Status)
    }
}
