package com.x.lasergrbl_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundaryPreviewPlannerTest {
    @Test
    fun createsLaserOffRectangleAroundBounds() {
        val bounds = GcodeBounds(
            minX = 1.0,
            maxX = 5.0,
            minY = 2.0,
            maxY = 8.0,
            minZ = 0.0,
            maxZ = 0.0,
            hasMotion = true,
        )

        val lines = BoundaryPreviewPlanner.plan(bounds, BoundaryPreviewSettings(feedRate = 1500))
        val commands = lines.map { it.command }

        assertEquals("M5", commands[1])
        assertEquals("G21", commands[2])
        assertEquals("G90", commands[3])
        assertEquals("G1 X1.000 Y2.000 F1500", commands[4])
        assertEquals("G1 X5.000 Y2.000 F1500", commands[5])
        assertEquals("G1 X5.000 Y8.000 F1500", commands[6])
        assertEquals("G1 X1.000 Y8.000 F1500", commands[7])
        assertEquals("G1 X1.000 Y2.000 F1500", commands[8])
        assertEquals("M5", commands.last())
        assertTrue(commands.none { it.startsWith("M3") || it.startsWith("M4") })
    }

    @Test
    fun supportsPadding() {
        val bounds = GcodeBounds(
            minX = 1.0,
            maxX = 5.0,
            minY = 2.0,
            maxY = 8.0,
            minZ = 0.0,
            maxZ = 0.0,
            hasMotion = true,
        )

        val commands = BoundaryPreviewPlanner
            .plan(bounds, BoundaryPreviewSettings(feedRate = 1000, paddingMm = 0.5))
            .map { it.command }

        assertEquals("G1 X0.500 Y1.500 F1000", commands[4])
        assertEquals("G1 X5.500 Y8.500 F1000", commands[6])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDegenerateBounds() {
        BoundaryPreviewPlanner.plan(
            GcodeBounds(
                minX = 1.0,
                maxX = 1.0,
                minY = 2.0,
                maxY = 8.0,
                minZ = 0.0,
                maxZ = 0.0,
                hasMotion = true,
            )
        )
    }
}
