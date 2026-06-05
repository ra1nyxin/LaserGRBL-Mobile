package com.x.lasergrbl_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgToGcodeConverterTest {
    @Test
    fun convertsLinePathToGcode() {
        val svg = """<svg><path d="M0 0 L10 0 L10 10 Z"/></svg>"""

        val result = SvgToGcodeConverter.convert(
            svg,
            SvgGcodeSettings(widthMm = 20.0, feedRate = 900, travelRate = 2500, power = 300)
        )

        assertEquals(1, result.pathCount)
        assertEquals(3, result.segmentCount)
        assertTrue(result.lines.any { it.command == "G0 X0.000 Y20.000 F2500" })
        assertTrue(result.lines.any { it.command == "M4 S300" })
        assertTrue(result.lines.any { it.command == "G1 X20.000 Y20.000 S300 F900" })
        assertTrue(result.lines.any { it.command == "G1 X20.000 Y0.000 S300 F900" })
        assertTrue(result.lines.any { it.command == "G1 X0.000 Y20.000 S300 F900" })
    }

    @Test
    fun supportsRelativeCommandsAndHorizontalVerticalLines() {
        val svg = """<svg><path d="m 5 5 h 10 v 5 l -10 0 z"/></svg>"""

        val result = SvgToGcodeConverter.convert(svg, SvgGcodeSettings(widthMm = 10.0))

        assertEquals(4, result.segmentCount)
        assertTrue(result.unsupportedCommands.isEmpty())
        assertTrue(result.lines.any { it.command == "G1 X10.000 Y5.000 S250 F1000" })
        assertTrue(result.lines.any { it.command == "G1 X10.000 Y0.000 S250 F1000" })
    }

    @Test
    fun flattensCubicCurveCommands() {
        val svg = """<svg><path d="M0 0 L10 0 C 10 10 20 10 20 0"/></svg>"""

        val result = SvgToGcodeConverter.convert(svg)

        assertTrue(result.unsupportedCommands.isEmpty())
        assertTrue(result.segmentCount > 2)
        assertTrue(result.lines.any { it.command.startsWith("G1 X30.000") })
    }

    @Test
    fun flattensQuadraticAndSmoothCurveCommands() {
        val svg = """<svg><path d="M0 0 Q 5 10 10 0 T 20 0"/></svg>"""

        val result = SvgToGcodeConverter.convert(svg, SvgGcodeSettings(widthMm = 20.0))

        assertTrue(result.unsupportedCommands.isEmpty())
        assertTrue(result.segmentCount > 4)
        assertTrue(result.lines.any { it.command.startsWith("G1 X20.000") })
    }

    @Test
    fun convertsBasicShapeElements() {
        val svg = """
            <svg>
                <rect x="0" y="0" width="10" height="5"/>
                <circle cx="20" cy="5" r="2"/>
                <ellipse cx="30" cy="5" rx="3" ry="1"/>
                <polyline points="40,0 45,5 50,0"/>
                <polygon points="60,0 65,5 70,0"/>
            </svg>
        """.trimIndent()

        val result = SvgToGcodeConverter.convert(svg, SvgGcodeSettings(widthMm = 70.0))

        assertEquals(5, result.pathCount)
        assertTrue(result.segmentCount > 30)
        assertTrue(result.unsupportedCommands.isEmpty())
        val bounds = GcodeParser.estimateBounds(result.lines)
        assertEquals(0.0, bounds.minX, 0.0001)
        assertEquals(70.0, bounds.maxX, 0.0001)
        assertEquals(0.0, bounds.minY, 0.0001)
        assertEquals(7.0, bounds.maxY, 0.0001)
    }

    @Test
    fun appliesNestedTransformsToShapesAndPaths() {
        val svg = """
            <svg>
                <g transform="translate(10, 5)">
                    <path d="M0 0 L10 0" transform="scale(2)"/>
                    <rect x="0" y="10" width="5" height="5"/>
                </g>
            </svg>
        """.trimIndent()

        val result = SvgToGcodeConverter.convert(svg, SvgGcodeSettings(widthMm = 20.0))
        val bounds = GcodeParser.estimateBounds(result.lines)

        assertEquals(2, result.pathCount)
        assertEquals(0.0, bounds.minX, 0.0001)
        assertEquals(20.0, bounds.maxX, 0.0001)
        assertEquals(0.0, bounds.minY, 0.0001)
        assertEquals(15.0, bounds.maxY, 0.0001)
    }

    @Test
    fun multipleSubPathsDoNotBurnTravelBetweenShapes() {
        val svg = """<svg><path d="M0 0 L10 0 M20 0 L30 0"/></svg>"""

        val result = SvgToGcodeConverter.convert(svg, SvgGcodeSettings(widthMm = 30.0))
        val commands = result.lines.map { it.command }
        val secondStart = commands.indexOf("G0 X20.000 Y0.000 F3000")

        assertTrue(secondStart > 0)
        assertEquals("M5", commands[secondStart - 1])
        assertTrue(commands.none { it == "G1 X20.000 Y0.000 S250 F1000" })
    }

    @Test
    fun generatedSvgGcodeCanBeParsedForBounds() {
        val svg = """<svg><path d="M0,0 L5,0 L5,5"/></svg>"""

        val result = SvgToGcodeConverter.convert(svg, SvgGcodeSettings(widthMm = 10.0))
        val bounds = GcodeParser.estimateBounds(result.lines)

        assertTrue(bounds.hasMotion)
        assertEquals(0.0, bounds.minX, 0.0001)
        assertEquals(10.0, bounds.maxX, 0.0001)
        assertEquals(0.0, bounds.minY, 0.0001)
        assertEquals(10.0, bounds.maxY, 0.0001)
    }
}
