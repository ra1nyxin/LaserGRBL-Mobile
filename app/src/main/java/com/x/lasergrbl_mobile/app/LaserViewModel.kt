package com.x.lasergrbl_mobile.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.x.lasergrbl_mobile.core.BoundaryPreviewPlanner
import com.x.lasergrbl_mobile.core.BoundaryPreviewSettings
import com.x.lasergrbl_mobile.core.GcodeBounds
import com.x.lasergrbl_mobile.core.GcodeLine
import com.x.lasergrbl_mobile.core.GcodeParser
import com.x.lasergrbl_mobile.core.GcodeStreamer
import com.x.lasergrbl_mobile.core.GrayRaster
import com.x.lasergrbl_mobile.core.GrblParser
import com.x.lasergrbl_mobile.core.GrblStatus
import com.x.lasergrbl_mobile.core.ImageGcodeSettings
import com.x.lasergrbl_mobile.core.ImageDitherMode
import com.x.lasergrbl_mobile.core.ImageMaterialPreset
import com.x.lasergrbl_mobile.core.ImageScanDirection
import com.x.lasergrbl_mobile.core.ImageToGcodeConverter
import com.x.lasergrbl_mobile.core.Response
import com.x.lasergrbl_mobile.core.StreamEvent
import com.x.lasergrbl_mobile.core.StreamProgress
import com.x.lasergrbl_mobile.core.SvgGcodeSettings
import com.x.lasergrbl_mobile.core.SvgToGcodeConverter
import com.x.lasergrbl_mobile.serial.SerialDeviceInfo
import com.x.lasergrbl_mobile.serial.SerialState
import com.x.lasergrbl_mobile.serial.UsbSerialController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ThemeMode {
    System, Light, Dark
}

data class JobFileState(
    val fileName: String = "未选择文件",
    val lines: List<GcodeLine> = emptyList(),
    val bounds: GcodeBounds? = null,
    val error: String? = null,
    val source: String = "G-code",
    val note: String? = null,
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
    val baudRateInput: String = "115200",
    val jogStep: Double = 1.0,
    val feedRate: Int = 1200,
    val laserPower: Int = 50,
    val imageWidthMm: Double = 30.0,
    val imageLineStepMm: Double = 0.12,
    val imageFeedRate: Int = 1200,
    val imageTravelRate: Int = 3000,
    val imageMaxPower: Int = 350,
    val imageThreshold: Int = 18,
    val imageGamma: Double = 1.0,
    val imageDitherMode: ImageDitherMode = ImageDitherMode.None,
    val imageScanDirection: ImageScanDirection = ImageScanDirection.Horizontal,
    val imageMaterialPreset: ImageMaterialPreset = ImageMaterialPreset.Custom,
    val imageBidirectional: Boolean = true,
    val imageInvert: Boolean = false,
    val svgColorLayering: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val safetyArmed: Boolean = false,
)

class LaserViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("lasergrbl_mobile", Application.MODE_PRIVATE)
    private val serialController = UsbSerialController(application, viewModelScope)
    private val streamer = GcodeStreamer(viewModelScope, serialController)

    private val _uiState = MutableStateFlow(LaserUiState())
    val uiState: StateFlow<LaserUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = _uiState.value.copy(
            themeMode = loadThemeMode(),
            baudRateInput = loadBaudRateInput(),
        )
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
        val baudRate = parsedBaudRate()
        if (baudRate == null) {
            appendLog("波特率无效，请输入 1200 到 2000000 之间的整数。")
            return
        }
        appendLog("请求连接：${device.name}，波特率 $baudRate")
        serialController.requestOpen(device.driver, baudRate)
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
                    _uiState.value = _uiState.value.copy(job = JobFileState(name, lines, bounds, source = "G-code"))
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

    fun loadImageAsGcode(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val bitmap = decodeScaledBitmap(uri, maxLongSide = 900)
                val raster = bitmap.toGrayRaster()
                val settings = currentImageSettings()
                val result = ImageToGcodeConverter.convert(raster, settings)
                val bounds = GcodeParser.estimateBounds(result.lines)
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "图片文件"
                val note = "已转换 ${raster.width}x${raster.height} 灰度图，烧蚀点 ${result.burnedPixels}，尺寸 ${fmt(result.widthMm)} x ${fmt(result.heightMm)} mm"
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        job = JobFileState(
                            fileName = "$name -> G-code",
                            lines = result.lines,
                            bounds = bounds,
                            source = "图片转换",
                            note = note,
                        )
                    )
                    appendLog(note)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(job = JobFileState(error = t.message, source = "图片转换"))
                    appendLog("图片转换失败：${t.message ?: "未知错误"}")
                }
            }
        }
    }

    fun loadSvgAsGcode(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val svg = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取 SVG 文件")
                val result = SvgToGcodeConverter.convert(svg, currentSvgSettings())
                val bounds = GcodeParser.estimateBounds(result.lines)
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "SVG 文件"
                val unsupported = if (result.unsupportedCommands.isEmpty()) {
                    "无"
                } else {
                    result.unsupportedCommands.joinToString("")
                }
                val layerSummary = result.layers.joinToString("；") { layer ->
                    "${layer.label} 路径${layer.pathCount} S${layer.power} F${layer.feedRate}"
                }
                val note = "已转换 SVG：图层 ${result.layerCount}，路径 ${result.pathCount}，线段 ${result.segmentCount}，跳过命令 $unsupported。$layerSummary"
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        job = JobFileState(
                            fileName = "$name -> G-code",
                            lines = result.lines,
                            bounds = bounds,
                            source = "SVG 转换",
                            note = note,
                        )
                    )
                    appendLog(note)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(job = JobFileState(error = t.message, source = "SVG 转换"))
                    appendLog("SVG 转换失败：${t.message ?: "未知错误"}")
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

    fun previewBoundary() {
        val state = _uiState.value
        if (!state.serial.connected) {
            appendLog("请先连接串口。")
            return
        }
        if (state.progress.running) {
            appendLog("当前已有任务在发送，请先停止或等待完成。")
            return
        }
        if (!state.safetyArmed) {
            appendLog("请先开启安全确认，再预跑边界。")
            return
        }
        val bounds = state.job.bounds
        if (bounds == null || !bounds.hasMotion) {
            appendLog("当前任务没有可用运动范围，无法预跑边界。")
            return
        }
        if (bounds.width <= 0.0 || bounds.height <= 0.0) {
            appendLog("当前任务 XY 边界尺寸过小，无法生成边界框。")
            return
        }

        val lines = runCatching {
            BoundaryPreviewPlanner.plan(
                bounds,
                BoundaryPreviewSettings(feedRate = state.feedRate.coerceIn(10, 20_000))
            )
        }.getOrElse { t ->
            appendLog("边界框生成失败：${t.message ?: "未知错误"}")
            return
        }

        appendLog(
            "开始边界框预跑：X ${fmt(bounds.minX)} ~ ${fmt(bounds.maxX)}，Y ${fmt(bounds.minY)} ~ ${fmt(bounds.maxY)}，激光保持关闭。"
        )
        streamer.start(lines)
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

    fun setImageWidthMm(value: Double) {
        _uiState.value = _uiState.value.copy(imageWidthMm = value.coerceIn(1.0, 500.0))
    }

    fun setImageLineStepMm(value: Double) {
        _uiState.value = _uiState.value.copy(
            imageLineStepMm = value.coerceIn(0.03, 2.0),
            imageMaterialPreset = ImageMaterialPreset.Custom,
        )
    }

    fun setImageFeedRate(value: Int) {
        _uiState.value = _uiState.value.copy(
            imageFeedRate = value.coerceIn(10, 20000),
            imageMaterialPreset = ImageMaterialPreset.Custom,
        )
    }

    fun setImageTravelRate(value: Int) {
        _uiState.value = _uiState.value.copy(
            imageTravelRate = value.coerceIn(10, 20000),
            imageMaterialPreset = ImageMaterialPreset.Custom,
        )
    }

    fun setImageMaxPower(value: Int) {
        _uiState.value = _uiState.value.copy(
            imageMaxPower = value.coerceIn(1, 1000),
            imageMaterialPreset = ImageMaterialPreset.Custom,
        )
    }

    fun setImageThreshold(value: Int) {
        _uiState.value = _uiState.value.copy(
            imageThreshold = value.coerceIn(0, 255),
            imageMaterialPreset = ImageMaterialPreset.Custom,
        )
    }

    fun setImageGamma(value: Double) {
        _uiState.value = _uiState.value.copy(
            imageGamma = value.coerceIn(0.1, 5.0),
            imageMaterialPreset = ImageMaterialPreset.Custom,
        )
    }

    fun setImageDitherMode(value: ImageDitherMode) {
        _uiState.value = _uiState.value.copy(
            imageDitherMode = value,
            imageMaterialPreset = ImageMaterialPreset.Custom,
        )
    }

    fun setImageScanDirection(value: ImageScanDirection) {
        _uiState.value = _uiState.value.copy(imageScanDirection = value)
    }

    fun setImageMaterialPreset(value: ImageMaterialPreset) {
        _uiState.value = if (value == ImageMaterialPreset.Custom) {
            _uiState.value.copy(imageMaterialPreset = value)
        } else {
            _uiState.value.copy(
                imageMaterialPreset = value,
                imageLineStepMm = value.lineStepMm,
                imageFeedRate = value.feedRate,
                imageTravelRate = value.travelRate,
                imageMaxPower = value.maxPower,
                imageThreshold = value.burnThreshold,
                imageGamma = value.gamma,
                imageDitherMode = value.ditherMode,
            )
        }
    }

    fun setImageBidirectional(value: Boolean) {
        _uiState.value = _uiState.value.copy(imageBidirectional = value)
    }

    fun setImageInvert(value: Boolean) {
        _uiState.value = _uiState.value.copy(imageInvert = value)
    }

    fun setSvgColorLayering(value: Boolean) {
        _uiState.value = _uiState.value.copy(svgColorLayering = value)
    }

    fun setThemeMode(value: ThemeMode) {
        preferences.edit().putString(KEY_THEME_MODE, value.name).apply()
        _uiState.value = _uiState.value.copy(themeMode = value)
    }

    fun setSafetyArmed(value: Boolean) {
        _uiState.value = _uiState.value.copy(safetyArmed = value)
    }

    fun setManualCommand(value: String) {
        _uiState.value = _uiState.value.copy(manualCommand = value)
    }

    fun setBaudRateInput(value: String) {
        val sanitized = value.filter { it.isDigit() }.take(7)
        preferences.edit().putString(KEY_BAUD_RATE, sanitized.ifBlank { DEFAULT_BAUD_RATE }).apply()
        _uiState.value = _uiState.value.copy(baudRateInput = sanitized)
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

    private fun currentImageSettings(): ImageGcodeSettings {
        val state = _uiState.value
        return ImageGcodeSettings(
            widthMm = state.imageWidthMm,
            lineStepMm = state.imageLineStepMm,
            feedRate = state.imageFeedRate,
            travelRate = state.imageTravelRate,
            maxPower = state.imageMaxPower,
            burnThreshold = state.imageThreshold,
            gamma = state.imageGamma,
            ditherMode = state.imageDitherMode,
            scanDirection = state.imageScanDirection,
            bidirectional = state.imageBidirectional,
            invert = state.imageInvert,
        )
    }

    private fun currentSvgSettings(): SvgGcodeSettings {
        val state = _uiState.value
        return SvgGcodeSettings(
            widthMm = state.imageWidthMm,
            feedRate = state.imageFeedRate,
            travelRate = state.imageTravelRate,
            power = state.imageMaxPower,
            colorLayering = state.svgColorLayering,
        )
    }

    private fun parsedBaudRate(): Int? {
        val baudRate = _uiState.value.baudRateInput.toIntOrNull() ?: return null
        return baudRate.takeIf { it in 1200..2_000_000 }
    }

    private fun decodeScaledBitmap(uri: Uri, maxLongSide: Int): Bitmap {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: error("无法读取图片")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("无法识别图片尺寸")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxLongSide)
        }
        val decoded = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("无法解码图片")
        if (decoded.width <= maxLongSide && decoded.height <= maxLongSide) {
            return decoded
        }

        val ratio = maxLongSide.toDouble() / maxOf(decoded.width, decoded.height).toDouble()
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun sampleSize(width: Int, height: Int, maxLongSide: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxLongSide * 2) {
            sample *= 2
        }
        return sample
    }

    private fun Bitmap.toGrayRaster(): GrayRaster {
        val pixels = IntArray(width * height)
        val gray = IntArray(pixels.size)
        getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = color shr 16 and 0xff
            val g = color shr 8 and 0xff
            val b = color and 0xff
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return GrayRaster(width, height, gray)
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

    private fun loadThemeMode(): ThemeMode {
        val raw = preferences.getString(KEY_THEME_MODE, ThemeMode.System.name)
        return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.System
    }

    private fun loadBaudRateInput(): String {
        val raw = preferences.getString(KEY_BAUD_RATE, DEFAULT_BAUD_RATE).orEmpty()
        val sanitized = raw.filter { it.isDigit() }.take(7)
        return sanitized.takeIf { it.toIntOrNull() in 1200..2_000_000 } ?: DEFAULT_BAUD_RATE
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_BAUD_RATE = "baud_rate"
        const val DEFAULT_BAUD_RATE = "115200"
    }
}

private fun fmt(value: Double): String = "%.2f".format(value)
