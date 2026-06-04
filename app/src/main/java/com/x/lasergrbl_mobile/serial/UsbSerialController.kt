package com.x.lasergrbl_mobile.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.x.lasergrbl_mobile.core.GrblTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class SerialDeviceInfo(
    val id: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val driver: UsbSerialDriver,
)

data class SerialState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val deviceName: String = "未连接",
    val baudRate: Int = 115200,
    val error: String? = null,
)

class UsbSerialController(
    private val context: Context,
    private val scope: CoroutineScope,
) : GrblTransport {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var readJob: Job? = null
    private var pendingDriver: UsbSerialDriver? = null

    private val _state = MutableStateFlow(SerialState())
    val state: StateFlow<SerialState> = _state

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val lines: SharedFlow<String> = _lines

    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val driver = pendingDriver
            pendingDriver = null
            if (granted && driver != null) {
                scope.launch { open(driver, _state.value.baudRate) }
            } else {
                _state.value = _state.value.copy(connecting = false, error = "USB 权限被拒绝")
            }
        }
    }

    init {
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    fun listDevices(): List<SerialDeviceInfo> {
        return UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .mapIndexed { index, driver ->
                val device = driver.device
                SerialDeviceInfo(
                    id = "${device.vendorId}:${device.productId}:$index",
                    name = device.productName ?: device.deviceName ?: "USB 串口设备",
                    vendorId = device.vendorId,
                    productId = device.productId,
                    driver = driver,
                )
            }
    }

    fun requestOpen(driver: UsbSerialDriver, baudRate: Int = 115200) {
        _state.value = _state.value.copy(connecting = true, baudRate = baudRate, error = null)
        val device: UsbDevice = driver.device
        if (usbManager.hasPermission(device)) {
            scope.launch { open(driver, baudRate) }
            return
        }

        pendingDriver = driver
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = PendingIntent.getBroadcast(context, 0, Intent(permissionAction), flags)
        usbManager.requestPermission(device, intent)
    }

    suspend fun open(driver: UsbSerialDriver, baudRate: Int = 115200) = withContext(Dispatchers.IO) {
        close()
        try {
            val connection = usbManager.openDevice(driver.device) ?: error("无法打开 USB 设备")
            val serialPort = driver.ports.firstOrNull() ?: error("没有可用串口")
            serialPort.open(connection)
            serialPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = serialPort
            _state.value = SerialState(
                connected = true,
                connecting = false,
                deviceName = driver.device.productName ?: driver.device.deviceName,
                baudRate = baudRate,
            )
            startReader()
        } catch (t: Throwable) {
            _state.value = SerialState(error = "连接失败：${t.message ?: "未知错误"}")
        }
    }

    fun close() {
        readJob?.cancel()
        readJob = null
        try {
            port?.close()
        } catch (_: IOException) {
        }
        port = null
        _state.value = _state.value.copy(connected = false, connecting = false, deviceName = "未连接")
    }

    override suspend fun writeLine(line: String) {
        writeRaw("$line\n")
    }

    override suspend fun writeRealtime(command: String) {
        writeRaw(command)
    }

    suspend fun writeRaw(text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        port?.write(bytes, 1000) ?: error("串口未连接")
    }

    fun release() {
        close()
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
    }

    private fun startReader() {
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            val line = StringBuilder()
            while (port != null) {
                try {
                    val count = port?.read(buffer, 200) ?: break
                    if (count > 0) {
                        val text = buffer.decodeToString(0, count)
                        for (ch in text) {
                            if (ch == '\n' || ch == '\r') {
                                val value = line.toString().trim()
                                if (value.isNotEmpty()) _lines.emit(value)
                                line.clear()
                            } else {
                                line.append(ch)
                            }
                        }
                    } else {
                        delay(10)
                    }
                } catch (t: Throwable) {
                    _state.value = _state.value.copy(connected = false, error = "串口读取中断：${t.message ?: "未知错误"}")
                    close()
                    break
                }
            }
        }
    }
}
