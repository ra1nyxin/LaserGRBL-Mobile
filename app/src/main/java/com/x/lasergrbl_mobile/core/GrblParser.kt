package com.x.lasergrbl_mobile.core

object GrblParser {
    fun parseStatus(line: String): GrblStatus? {
        if (!line.startsWith("<") || !line.endsWith(">")) return null

        val parts = line.removePrefix("<").removeSuffix(">").split('|')
        val state = parseState(parts.firstOrNull().orEmpty())
        var machinePosition: MachinePosition? = null
        var workPosition: MachinePosition? = null
        var feedRate: Int? = null
        var spindleSpeed: Int? = null
        var bufferAvailable: Int? = null

        for (part in parts.drop(1)) {
            when {
                part.startsWith("MPos:") -> machinePosition = parsePosition(part.removePrefix("MPos:"))
                part.startsWith("WPos:") -> workPosition = parsePosition(part.removePrefix("WPos:"))
                part.startsWith("FS:") -> {
                    val fs = part.removePrefix("FS:").split(',')
                    feedRate = fs.getOrNull(0)?.toIntOrNull()
                    spindleSpeed = fs.getOrNull(1)?.toIntOrNull()
                }
                part.startsWith("Bf:") -> {
                    bufferAvailable = part.removePrefix("Bf:").split(',').getOrNull(0)?.toIntOrNull()
                }
            }
        }

        return GrblStatus(
            state = state,
            machinePosition = machinePosition,
            workPosition = workPosition,
            feedRate = feedRate,
            spindleSpeed = spindleSpeed,
            bufferAvailable = bufferAvailable,
            raw = line,
        )
    }

    fun classifyResponse(line: String): Response {
        val trimmed = line.trim()
        return when {
            trimmed.equals("ok", ignoreCase = true) -> Response.Ok
            trimmed.startsWith("error:", ignoreCase = true) -> Response.Error(trimmed)
            trimmed.startsWith("ALARM:", ignoreCase = true) -> Response.Alarm(trimmed)
            trimmed.startsWith("<") && trimmed.endsWith(">") -> Response.Status(parseStatus(trimmed))
            trimmed.isBlank() -> Response.Empty
            else -> Response.Message(trimmed)
        }
    }

    private fun parseState(value: String): MachineState {
        val normalized = value.substringBefore(':')
        return MachineState.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
            ?: MachineState.Unknown
    }

    private fun parsePosition(value: String): MachinePosition? {
        val parts = value.split(',')
        return MachinePosition(
            x = parts.getOrNull(0)?.toDoubleOrNull() ?: return null,
            y = parts.getOrNull(1)?.toDoubleOrNull() ?: return null,
            z = parts.getOrNull(2)?.toDoubleOrNull() ?: return null,
        )
    }
}

sealed interface Response {
    data object Ok : Response
    data object Empty : Response
    data class Error(val message: String) : Response
    data class Alarm(val message: String) : Response
    data class Status(val status: GrblStatus?) : Response
    data class Message(val message: String) : Response
}
