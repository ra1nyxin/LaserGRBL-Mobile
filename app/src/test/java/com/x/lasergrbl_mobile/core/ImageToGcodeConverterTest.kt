package com.x.lasergrbl_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageToGcodeConverterTest {
    @Test
    fun blackPixelsBecomeLaserMoves() {
        val raster = GrayRaster(
            width = 2,
            height = 1,
            pixels = intArrayOf(0, 255),
        )

        val result = ImageToGcodeConverter.convert(
            raster,
            ImageGcodeSettings(
                widthMm = 10.0,
                lineStepMm = 1.0,
                feedRate = 800,
                travelRate = 2000,
                maxPower = 500,
                burnThreshold = 10,
            )
        )

        assertEquals(1, result.burnedPixels)
        assertTrue(result.lines.any { it.command == "M4 S500" })
        assertTrue(result.lines.any { it.command == "G1 X0.000 Y0.000 S500 F800" })
        assertTrue(result.lines.none { it.command.contains("X5.000") && it.command.startsWith("G1") })
    }

    @Test
    fun grayPixelMapsToProportionalPower() {
        val raster = GrayRaster(
            width = 1,
            height = 1,
            pixels = intArrayOf(128),
        )

        val result = ImageToGcodeConverter.convert(
            raster,
            ImageGcodeSettings(widthMm = 1.0, maxPower = 1000, burnThreshold = 0)
        )

        assertTrue(result.lines.any { it.command == "M4 S498" })
        assertTrue(result.lines.any { it.command == "G1 X0.000 Y0.000 S498 F1200" })
    }

    @Test
    fun bidirectionalRowsAlternateDirection() {
        val raster = GrayRaster(
            width = 3,
            height = 2,
            pixels = intArrayOf(
                0, 0, 0,
                0, 0, 0,
            ),
        )

        val result = ImageToGcodeConverter.convert(
            raster,
            ImageGcodeSettings(widthMm = 3.0, lineStepMm = 1.0, maxPower = 100, bidirectional = true)
        )

        val moves = result.lines.map { it.command }.filter { it.startsWith("G1") }
        assertEquals("G1 X0.000 Y0.000 S100 F1200", moves[0])
        assertEquals("G1 X2.000 Y1.000 S100 F1200", moves[3])
    }

    @Test
    fun thresholdCanSkipLightPixels() {
        val raster = GrayRaster(
            width = 2,
            height = 1,
            pixels = intArrayOf(245, 240),
        )

        val result = ImageToGcodeConverter.convert(
            raster,
            ImageGcodeSettings(widthMm = 2.0, burnThreshold = 16)
        )

        assertEquals(0, result.burnedPixels)
        assertTrue(result.lines.none { it.command.startsWith("M4") })
    }

    @Test
    fun generatedGcodeCanBeParsedForBounds() {
        val raster = GrayRaster(
            width = 2,
            height = 2,
            pixels = intArrayOf(
                0, 255,
                255, 0,
            ),
        )

        val result = ImageToGcodeConverter.convert(
            raster,
            ImageGcodeSettings(widthMm = 4.0, lineStepMm = 1.0, maxPower = 100)
        )
        val bounds = GcodeParser.estimateBounds(result.lines)

        assertTrue(bounds.hasMotion)
        assertEquals(0.0, bounds.minX, 0.0001)
        assertEquals(2.0, bounds.maxX, 0.0001)
        assertEquals(0.0, bounds.minY, 0.0001)
        assertEquals(2.0, bounds.maxY, 0.0001)
    }

    @Test
    fun gammaChangesMidtonePower() {
        val raster = GrayRaster(
            width = 1,
            height = 1,
            pixels = intArrayOf(128),
        )

        val linear = ImageToGcodeConverter.convert(
            raster,
            ImageGcodeSettings(widthMm = 1.0, maxPower = 1000, burnThreshold = 0, gamma = 1.0)
        )
        val darkerCurve = ImageToGcodeConverter.convert(
            raster,
            ImageGcodeSettings(widthMm = 1.0, maxPower = 1000, burnThreshold = 0, gamma = 2.0)
        )

        assertTrue(linear.lines.any { it.command == "G1 X0.000 Y0.000 S498 F1200" })
        assertTrue(darkerCurve.lines.any { it.command == "G1 X0.000 Y0.000 S248 F1200" })
    }

    @Test
    fun orderedDitherProducesBinaryPower() {
        val raster = GrayRaster(
            width = 4,
            height = 1,
            pixels = intArrayOf(128, 128, 128, 128),
        )

        val result = ImageToGcodeConverter.convert(
            raster,
            ImageGcodeSettings(
                widthMm = 4.0,
                maxPower = 500,
                burnThreshold = 0,
                ditherMode = ImageDitherMode.Ordered,
            )
        )
        val moves = result.lines.map { it.command }.filter { it.startsWith("G1") }

        assertTrue(moves.isNotEmpty())
        assertTrue(moves.all { it.contains("S500") })
    }

    @Test
    fun verticalScanMovesAlongYFirst() {
        val raster = GrayRaster(
            width = 2,
            height = 3,
            pixels = intArrayOf(
                0, 255,
                0, 255,
                0, 255,
            ),
        )

        val result = ImageToGcodeConverter.convert(
            raster,
            ImageGcodeSettings(
                widthMm = 2.0,
                lineStepMm = 1.0,
                maxPower = 100,
                scanDirection = ImageScanDirection.Vertical,
                bidirectional = false,
            )
        )
        val moves = result.lines.map { it.command }.filter { it.startsWith("G1") }

        assertEquals("G1 X0.000 Y0.000 S100 F1200", moves[0])
        assertEquals("G1 X0.000 Y1.000 S100 F1200", moves[1])
        assertEquals("G1 X0.000 Y2.000 S100 F1200", moves[2])
    }
}
