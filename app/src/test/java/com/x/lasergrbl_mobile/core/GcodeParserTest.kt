package com.x.lasergrbl_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeParserTest {
    @Test
    fun sanitizeRemovesComments() {
        assertEquals("G1 X1 Y2", GcodeParser.sanitize(" G1 X1 Y2 ; comment"))
        assertEquals("M3 S100", GcodeParser.sanitize("M3 S100 (laser on)"))
        assertEquals("", GcodeParser.sanitize("(only comment)"))
    }

    @Test
    fun parseDropsBlankLinesAndCountsLineNumbers() {
        val lines = GcodeParser.parse(
            """
            ; header
            G0 X0 Y0
            (comment)
            G1 X10 Y5
            """.trimIndent()
        )

        assertEquals(2, lines.size)
        assertEquals(2, lines[0].number)
        assertEquals("G0 X0 Y0", lines[0].command)
        assertEquals(4, lines[1].number)
    }

    @Test
    fun estimateBoundsFromMotion() {
        val lines = GcodeParser.parse("G0 X0 Y0\nG1 X10 Y5\nG1 X-2 Y7")
        val bounds = GcodeParser.estimateBounds(lines)

        assertTrue(bounds.hasMotion)
        assertEquals(-2.0, bounds.minX, 0.001)
        assertEquals(10.0, bounds.maxX, 0.001)
        assertEquals(0.0, bounds.minY, 0.001)
        assertEquals(7.0, bounds.maxY, 0.001)
    }

    @Test
    fun estimateBoundsDoesNotTreatG10AsG1Motion() {
        val lines = GcodeParser.parse("G10 X999 Y999\nG1 X2 Y3")
        val bounds = GcodeParser.estimateBounds(lines)

        assertTrue(bounds.hasMotion)
        assertEquals(2.0, bounds.minX, 0.001)
        assertEquals(2.0, bounds.maxX, 0.001)
        assertEquals(3.0, bounds.minY, 0.001)
        assertEquals(3.0, bounds.maxY, 0.001)
    }

    @Test
    fun linearMotionDetectionRequiresExactGToken() {
        assertTrue(GcodeParser.isLinearMotion("G0 X0"))
        assertTrue(GcodeParser.isLinearMotion("G01 X1"))
        assertEquals(false, GcodeParser.isLinearMotion("G10 L20 X0"))
        assertEquals(false, GcodeParser.isLinearMotion("G17"))
    }
}
