package com.x.lasergrbl_mobile.core

enum class MachineState {
    Idle, Run, Hold, Jog, Alarm, Door, Check, Home, Sleep, Unknown
}

data class MachinePosition(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
)

data class GrblStatus(
    val state: MachineState = MachineState.Unknown,
    val machinePosition: MachinePosition? = null,
    val workPosition: MachinePosition? = null,
    val feedRate: Int? = null,
    val spindleSpeed: Int? = null,
    val bufferAvailable: Int? = null,
    val raw: String = "",
) {
    val isBusy: Boolean
        get() = state == MachineState.Run || state == MachineState.Jog || state == MachineState.Hold
}

data class GcodeLine(
    val number: Int,
    val raw: String,
    val command: String,
    val byteLength: Int,
)

data class StreamProgress(
    val totalLines: Int = 0,
    val sentLines: Int = 0,
    val acknowledgedLines: Int = 0,
    val errorCount: Int = 0,
    val running: Boolean = false,
    val paused: Boolean = false,
    val currentLine: GcodeLine? = null,
) {
    val percent: Float
        get() = if (totalLines <= 0) 0f else acknowledgedLines.toFloat() / totalLines.toFloat()
}

data class StreamEvent(
    val kind: Kind,
    val message: String,
    val line: GcodeLine? = null,
) {
    enum class Kind {
        Info, Sent, Ok, Error, Paused, Resumed, Stopped, Finished
    }
}
