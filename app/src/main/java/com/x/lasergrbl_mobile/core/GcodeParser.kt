package com.x.lasergrbl_mobile.core

object GcodeParser {
    fun parse(text: String): List<GcodeLine> {
        return text
            .lineSequence()
            .mapIndexedNotNull { index, raw ->
                val command = sanitize(raw)
                if (command.isBlank()) {
                    null
                } else {
                    GcodeLine(
                        number = index + 1,
                        raw = raw,
                        command = command,
                        byteLength = command.toByteArray(Charsets.US_ASCII).size + 1,
                    )
                }
            }
            .toList()
    }

    fun sanitize(line: String): String {
        val withoutSemicolon = line.substringBefore(';')
        val out = StringBuilder()
        var inParen = false

        for (ch in withoutSemicolon) {
            when (ch) {
                '(' -> inParen = true
                ')' -> inParen = false
                else -> if (!inParen) out.append(ch)
            }
        }

        return out.toString().trim()
    }

    fun estimateBounds(lines: List<GcodeLine>): GcodeBounds {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var minX = 0.0
        var maxX = 0.0
        var minY = 0.0
        var maxY = 0.0
        var minZ = 0.0
        var maxZ = 0.0
        var seenMotion = false

        for (line in lines) {
            val nextX = valueAfter(line.command, 'X') ?: x
            val nextY = valueAfter(line.command, 'Y') ?: y
            val nextZ = valueAfter(line.command, 'Z') ?: z
            val moves = line.command.contains("G0", true) ||
                line.command.contains("G1", true) ||
                line.command.contains("G00", true) ||
                line.command.contains("G01", true)

            if (moves) {
                x = nextX
                y = nextY
                z = nextZ
                if (!seenMotion) {
                    minX = x
                    maxX = x
                    minY = y
                    maxY = y
                    minZ = z
                    maxZ = z
                    seenMotion = true
                } else {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                    minZ = minOf(minZ, z)
                    maxZ = maxOf(maxZ, z)
                }
            }
        }

        return GcodeBounds(minX, maxX, minY, maxY, minZ, maxZ, seenMotion)
    }

    private fun valueAfter(command: String, axis: Char): Double? {
        val index = command.indexOf(axis, ignoreCase = true)
        if (index < 0) return null

        val value = command
            .drop(index + 1)
            .takeWhile { it.isDigit() || it == '-' || it == '+' || it == '.' }

        return value.toDoubleOrNull()
    }
}

data class GcodeBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val minZ: Double,
    val maxZ: Double,
    val hasMotion: Boolean,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
}
