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
import kotlinx.coroutines.CancellationException
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
    private companion object {
        const val MAX_LINE_CHARS = 2048
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val portLock = Any()
    private var port: UsbSerialPort? = null
    private var readJob: Job? = null
    private var pendingDriver: UsbSerialDriver? = null
    private var activeDeviceName: String? = null

    private val _state = MutableStateFlow(SerialState())
    val state: StateFlow<SerialState> = _state

    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val lines: SharedFlow<String> = _lines

    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private var closing = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val driver = pendingDriver
                    pendingDriver = null
                    val sameDevice = driver != null && device?.deviceName == driver.device.deviceName
                    if (granted && driver != null && sameDevice) {
                        scope.launch { open(driver, _state.value.baudRate) }
                    } else {
                        _state.value = _state.value.copy(
                            connecting = false,
                            error = if (granted) "USB 权限回调设备不匹配，请重新连接" else "USB 权限被拒绝",
                        )
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val detachedName = detached?.deviceName
                    if (detachedName != null && pendingDriver?.device?.deviceName == detachedName) {
                        pendingDriver = null
                        _state.value = _state.value.copy(connecting = false, error = "USB 设备已拔出")
                    }
                    if (detachedName != null && detachedName == activeDeviceName) {
                        closeWithError("USB 设备已拔出")
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
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
        pendingDriver = null
        if (_state.value.connected || _state.value.connecting) {
            close()
        }
        _state.value = _state.value.copy(connecting = true, baudRate = baudRate, error = null)
        val device: UsbDevice = driver.device
        if (usbManager.hasPermission(device)) {
            scope.launch { open(driver, baudRate) }
            return
        }

        pendingDriver = driver
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = PendingIntent.getBroadcast(context, 0, Intent(permissionAction), flags)
        usbManager.requestPermission(device, intent)
    }

    suspend fun open(driver: UsbSerialDriver, baudRate: Int = 115200) = withContext(Dispatchers.IO) {
        close()
        var openedPort: UsbSerialPort? = null
        try {
            val connection = usbManager.openDevice(driver.device) ?: error("无法打开 USB 设备")
            val serialPort = driver.ports.firstOrNull() ?: error("没有可用串口")
            serialPort.open(connection)
            openedPort = serialPort
            serialPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            synchronized(portLock) {
                port = serialPort
                activeDeviceName = driver.device.deviceName
            }
            _state.value = SerialState(
                connected = true,
                connecting = false,
                deviceName = driver.device.productName ?: driver.device.deviceName,
                baudRate = baudRate,
            )
            startReader(serialPort, driver.device.deviceName)
        } catch (t: Throwable) {
            try {
                openedPort?.close()
            } catch (_: Throwable) {
            }
            synchronized(portLock) {
                if (port == openedPort) {
                    port = null
                    activeDeviceName = null
                }
            }
            _state.value = SerialState(error = "连接失败：${t.message ?: "未知错误"}")
        }
    }

    fun close() {
        closeInternal(error = null, cancelReader = true)
    }

    private fun closeWithError(message: String) {
        closeInternal(error = message, cancelReader = true)
    }

    private fun closeInternal(error: String?, cancelReader: Boolean) {
        closing = true
        val (jobToCancel, portToClose) = synchronized(portLock) {
            val job = readJob
            val activePort = port
            readJob = null
            port = null
            activeDeviceName = null
            job to activePort
        }
        if (cancelReader) {
            jobToCancel?.cancel()
        }
        try {
            portToClose?.close()
        } catch (_: IOException) {
        } catch (_: Throwable) {
        }
        _state.value = _state.value.copy(
            connected = false,
            connecting = false,
            deviceName = "未连接",
            error = error,
        )
        closing = false
    }

    override suspend fun writeLine(line: String) {
        writeRaw("$line\n")
    }

    override suspend fun writeRealtime(command: String) {
        writeRaw(command)
    }

    suspend fun writeRaw(text: String) = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val activePort = synchronized(portLock) { port } ?: error("串口未连接")
        try {
            activePort.write(bytes, 1000)
        } catch (t: Throwable) {
            closeInternal(error = "串口写入失败：${t.message ?: "未知错误"}", cancelReader = true)
            throw t
        }
    }

    fun release() {
        close()
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
    }

    private fun startReader(expectedPort: UsbSerialPort, expectedDeviceName: String) {
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            val line = StringBuilder()
            while (true) {
                try {
                    val activePort = synchronized(portLock) {
                        if (port === expectedPort && activeDeviceName == expectedDeviceName) port else null
                    } ?: break
                    val count = activePort.read(buffer, 200)
                    if (count > 0) {
                        val text = buffer.decodeToString(0, count)
                        for (ch in text) {
                            if (ch == '\n' || ch == '\r') {
                                val value = line.toString().trim()
                                if (value.isNotEmpty()) _lines.emit(value)
                                line.clear()
                            } else {
                                line.append(ch)
                                if (line.length > MAX_LINE_CHARS) {
                                    _lines.emit(line.toString())
                                    line.clear()
                                }
                            }
                        }
                    } else {
                        delay(10)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException || closing) {
                        break
                    }
                    closeInternal(error = "串口读取中断：${t.message ?: "未知错误"}", cancelReader = false)
                    break
                }
            }
        }
    }
}
