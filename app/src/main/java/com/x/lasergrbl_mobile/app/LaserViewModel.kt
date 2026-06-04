package com.x.lasergrbl_mobile.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.x.lasergrbl_mobile.core.GcodeBounds
import com.x.lasergrbl_mobile.core.GcodeLine
import com.x.lasergrbl_mobile.core.GcodeParser
import com.x.lasergrbl_mobile.core.GcodeStreamer
import com.x.lasergrbl_mobile.core.GrblParser
import com.x.lasergrbl_mobile.core.GrblStatus
import com.x.lasergrbl_mobile.core.Response
import com.x.lasergrbl_mobile.core.StreamEvent
import com.x.lasergrbl_mobile.core.StreamProgress
import com.x.lasergrbl_mobile.serial.SerialDeviceInfo
import com.x.lasergrbl_mobile.serial.SerialState
import com.x.lasergrbl_mobile.serial.UsbSerialController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JobFileState(
    val fileName: String = "未选择文件",
    val lines: List<GcodeLine> = emptyList(),
    val bounds: GcodeBounds? = null,
    val error: String? = null,
) {
    val loaded: Boolean get() = lines.isNotEmpty()
}

data class LaserUiState(
    val devices: List<SerialDeviceInfo> = emptyList(),
    val selectedDeviceId: String? = null,
    val serial: SerialState = SerialState(),
    val status: GrblStatus = GrblStatus(),
    val job: JobFileState = JobFileState(),
    val progress: StreamProgress = StreamProgress(),
    val log: List<String> = listOf("应用已启动，请连接 USB 串口设备。"),
    val manualCommand: String = "",
    val jogStep: Double = 1.0,
    val feedRate: Int = 1200,
    val laserPower: Int = 50,
    val safetyArmed: Boolean = false,
)

class LaserViewModel(application: Application) : AndroidViewModel(application) {
    private val serialController = UsbSerialController(application, viewModelScope)
    private val streamer = GcodeStreamer(viewModelScope, serialController)

    private val _uiState = MutableStateFlow(LaserUiState())
    val uiState: StateFlow<LaserUiState> = _uiState.asStateFlow()

    init {
        refreshDevices()
        viewModelScope.launch {
            serialController.state.collect { serial ->
                _uiState.value = _uiState.value.copy(serial = serial)
            }
        }
        viewModelScope.launch {
            serialController.lines.collect { line ->
                handleIncomingLine(line)
            }
        }
        viewModelScope.launch {
            streamer.progress.collect { progress ->
                _uiState.value = _uiState.value.copy(progress = progress)
            }
        }
        viewModelScope.launch {
            streamer.events.collect { event ->
                appendLog(event.message)
            }
        }
    }

    fun refreshDevices() {
        _uiState.value = _uiState.value.copy(devices = serialController.listDevices())
    }

    fun selectDevice(deviceId: String) {
        _uiState.value = _uiState.value.copy(selectedDeviceId = deviceId)
    }

    fun connectSelected() {
        val state = _uiState.value
        val device = state.devices.firstOrNull { it.id == state.selectedDeviceId } ?: state.devices.firstOrNull()
        if (device == null) {
            appendLog("没有发现 USB 串口设备，请确认手机支持 OTG 并已连接雕刻机。")
            return
        }
        appendLog("请求连接：${device.name}")
        serialController.requestOpen(device.driver)
    }

    fun disconnect() {
        streamer.stop()
        serialController.close()
        appendLog("已断开串口。")
    }

    fun loadGcode(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件")
                val lines = GcodeParser.parse(text)
                val bounds = GcodeParser.estimateBounds(lines)
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "G-code 文件"
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(job = JobFileState(name, lines, bounds))
                    appendLog("已读取 $name，共 ${lines.size} 条有效 G-code。")
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(job = JobFileState(error = t.message))
                    appendLog("读取文件失败：${t.message ?: "未知错误"}")
                }
            }
        }
    }

    fun startJob() {
        val state = _uiState.value
        if (!state.serial.connected) {
            appendLog("请先连接串口。")
            return
        }
        if (!state.safetyArmed) {
            appendLog("请先开启安全确认，再开始加工。")
            return
        }
        if (state.job.lines.isEmpty()) {
            appendLog("请先选择 G-code 文件。")
            return
        }
        appendLog("开始发送任务：${state.job.fileName}")
        streamer.start(state.job.lines)
    }

    fun pauseJob() = streamer.pause()
    fun resumeJob() = streamer.resume()
    fun stopJob() {
        streamer.stop()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { serialController.writeRealtime("!") }
        }
        appendLog("已请求停止发送，并发送暂停指令。")
    }

    fun queryStatus() = writeRealtime("?")
    fun unlock() = writeLine("\$X")
    fun home() = writeLine("\$H")
    fun reset() = writeRealtime("\u0018")
    fun laserOff() = writeLine("M5")

    fun laserTest() {
        val power = _uiState.value.laserPower.coerceIn(1, 1000)
        writeLine("M3 S$power")
        appendLog("已发送弱光测试 M3 S$power，请注意护目镜和防火。")
    }

    fun jog(axis: Char, direction: Int) {
        val state = _uiState.value
        val distance = state.jogStep * direction
        writeLine("\$J=G91 G21 $axis${formatDistance(distance)} F${state.feedRate}")
    }

    fun setJogStep(value: Double) {
        _uiState.value = _uiState.value.copy(jogStep = value)
    }

    fun setFeedRate(value: Int) {
        _uiState.value = _uiState.value.copy(feedRate = value.coerceIn(10, 20000))
    }

    fun setLaserPower(value: Int) {
        _uiState.value = _uiState.value.copy(laserPower = value.coerceIn(1, 1000))
    }

    fun setSafetyArmed(value: Boolean) {
        _uiState.value = _uiState.value.copy(safetyArmed = value)
    }

    fun setManualCommand(value: String) {
        _uiState.value = _uiState.value.copy(manualCommand = value)
    }

    fun sendManualCommand() {
        val command = _uiState.value.manualCommand.trim()
        if (command.isBlank()) return
        writeLine(command)
        _uiState.value = _uiState.value.copy(manualCommand = "")
    }

    fun sendRealtime(command: String) = writeRealtime(command)

    private fun handleIncomingLine(line: String) {
        appendLog("← $line")
        val response = GrblParser.classifyResponse(line)
        when (response) {
            is Response.Status -> response.status?.let { status ->
                _uiState.value = _uiState.value.copy(status = status)
            }
            is Response.Ok,
            is Response.Error,
            is Response.Alarm -> viewModelScope.launch {
                streamer.enqueueResponse(response)
            }
            else -> Unit
        }
    }

    private fun writeLine(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                serialController.writeLine(command)
                appendLog("→ $command")
            } catch (t: Throwable) {
                appendLog("发送失败：${t.message ?: "未知错误"}")
            }
        }
    }

    private fun writeRealtime(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                serialController.writeRealtime(command)
                appendLog("→ 实时指令 ${commandLabel(command)}")
            } catch (t: Throwable) {
                appendLog("发送失败：${t.message ?: "未知错误"}")
            }
        }
    }

    private fun appendLog(message: String) {
        val current = _uiState.value.log
        _uiState.value = _uiState.value.copy(log = (current + message).takeLast(300))
    }

    private fun formatDistance(value: Double): String {
        return "%+.3f".format(value)
    }

    private fun commandLabel(command: String): String {
        return when (command) {
            "!" -> "暂停"
            "~" -> "继续"
            "?" -> "查询状态"
            "\u0018" -> "软复位"
            else -> command
        }
    }

    override fun onCleared() {
        serialController.release()
        super.onCleared()
    }
}
