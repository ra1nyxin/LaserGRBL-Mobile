package com.x.lasergrbl_mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.x.lasergrbl_mobile.app.LaserUiState
import com.x.lasergrbl_mobile.app.LaserViewModel
import com.x.lasergrbl_mobile.core.GcodeBounds
import com.x.lasergrbl_mobile.core.GcodeLine
import com.x.lasergrbl_mobile.core.MachinePosition
import com.x.lasergrbl_mobile.ui.theme.LaserGRBLMobileTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LaserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LaserGRBLMobileTheme {
                val state by viewModel.uiState.collectAsState()
                LaserApp(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun LaserApp(state: LaserUiState, viewModel: LaserViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("连接", "控制", "文件", "发送", "日志")

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 28.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("LaserGRBL Mobile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.serial.connected) "已连接：${state.serial.deviceName}" else "未连接设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.serial.connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                        )
                    }
                    StatusBadge(state)
                }
                TabRow(selectedTabIndex = tab) {
                    titles.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (tab) {
                0 -> ConnectPage(state, viewModel)
                1 -> ControlPage(state, viewModel)
                2 -> FilePage(state, viewModel)
                3 -> SendPage(state, viewModel)
                else -> LogPage(state)
            }
        }
    }
}

@Composable
private fun StatusBadge(state: LaserUiState) {
    val label = state.status.state.name
    AssistChip(onClick = {}, label = { Text("状态：$label") })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectPage(state: LaserUiState, viewModel: LaserViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.devices.firstOrNull { it.id == state.selectedDeviceId } ?: state.devices.firstOrNull()

    PageColumn {
        SectionCard("串口连接") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = viewModel::refreshDevices, modifier = Modifier.weight(1f)) {
                    Text("刷新设备")
                }
                if (state.serial.connected) {
                    OutlinedButton(onClick = viewModel::disconnect, modifier = Modifier.weight(1f)) { Text("断开") }
                } else {
                    Button(onClick = viewModel::connectSelected, modifier = Modifier.weight(1f)) { Text("连接") }
                }
            }
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    value = selected?.let { "${it.name}  VID:${it.vendorId} PID:${it.productId}" } ?: "未发现 USB 串口设备",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("USB 串口设备") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    state.devices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text("${device.name}  VID:${device.vendorId} PID:${device.productId}") },
                            onClick = {
                                viewModel.selectDevice(device.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
            if (state.serial.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.serial.error, color = MaterialTheme.colorScheme.tertiary)
            }
        }

        SectionCard("连接提示") {
            Text("1. 手机需要支持 USB OTG。")
            Text("2. 常见主控使用 CH340、CP2102、FTDI 或 CDC 串口。")
            Text("3. 默认波特率 115200，适配大多数 GRBL 固件。")
            Text("4. 开始加工前务必戴护目镜并确认急停有效。")
        }
    }
}

@Composable
private fun ControlPage(state: LaserUiState, viewModel: LaserViewModel) {
    PageColumn {
        SectionCard("设备状态") {
            Text("状态：${state.status.state}")
            PositionText("机器坐标", state.status.machinePosition)
            PositionText("工件坐标", state.status.workPosition)
            Text("进给：${state.status.feedRate ?: "-"}    激光/主轴：${state.status.spindleSpeed ?: "-"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = viewModel::queryStatus, modifier = Modifier.weight(1f)) { Text("查询 ?") }
                OutlinedButton(onClick = viewModel::unlock, modifier = Modifier.weight(1f)) { Text("解锁 \$X") }
                OutlinedButton(onClick = viewModel::home, modifier = Modifier.weight(1f)) { Text("回零 \$H") }
            }
        }

        SectionCard("手动移动") {
            Text("步长：${state.jogStep} mm")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.1, 1.0, 10.0).forEach { step ->
                    OutlinedButton(onClick = { viewModel.setJogStep(step) }) { Text("${step}mm") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { viewModel.jog('X', -1) }, modifier = Modifier.weight(1f)) { Text("X-") }
                Button(onClick = { viewModel.jog('Y', 1) }, modifier = Modifier.weight(1f)) { Text("Y+") }
                Button(onClick = { viewModel.jog('X', 1) }, modifier = Modifier.weight(1f)) { Text("X+") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { viewModel.jog('Y', -1) }, modifier = Modifier.weight(1f)) { Text("Y-") }
                Button(onClick = { viewModel.jog('Z', 1) }, modifier = Modifier.weight(1f)) { Text("Z+") }
                Button(onClick = { viewModel.jog('Z', -1) }, modifier = Modifier.weight(1f)) { Text("Z-") }
            }
        }

        SectionCard("激光与安全") {
            Text("弱光功率：S${state.laserPower}")
            Slider(value = state.laserPower.toFloat(), onValueChange = { viewModel.setLaserPower(it.toInt()) }, valueRange = 1f..1000f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = viewModel::laserTest, modifier = Modifier.weight(1f)) { Text("弱光测试") }
                OutlinedButton(onClick = viewModel::laserOff, modifier = Modifier.weight(1f)) { Text("关光 M5") }
                OutlinedButton(onClick = viewModel::reset, modifier = Modifier.weight(1f)) { Text("复位") }
            }
        }
    }
}

@Composable
private fun FilePage(state: LaserUiState, viewModel: LaserViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.loadGcode(uri)
    }

    PageColumn {
        SectionCard("G-code 文件") {
            Button(onClick = { launcher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) {
                Text("选择 .nc / .gcode 文件")
            }
            Spacer(Modifier.height(8.dp))
            Text("文件：${state.job.fileName}")
            Text("有效行数：${state.job.lines.size}")
            state.job.bounds?.let { bounds ->
                if (bounds.hasMotion) {
                    Text("范围：X ${fmt(bounds.minX)} ~ ${fmt(bounds.maxX)}，Y ${fmt(bounds.minY)} ~ ${fmt(bounds.maxY)}")
                    Text("尺寸：${fmt(bounds.width)} x ${fmt(bounds.height)} mm")
                    GcodePreview(state.job.lines, bounds)
                }
            }
            if (state.job.error != null) Text(state.job.error, color = MaterialTheme.colorScheme.tertiary)
        }

        SectionCard("转换功能") {
            Text("当前版本已支持 G-code 文件读取、清理注释、范围估算和轨迹预览。图片/SVG 转 G-code 将放入下一阶段，避免在没有硬件实测前一次性加入不可控的加工风险。")
        }
    }
}

@Composable
private fun SendPage(state: LaserUiState, viewModel: LaserViewModel) {
    PageColumn {
        SectionCard("任务发送") {
            LinearProgressIndicator(progress = { state.progress.percent }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("进度：${state.progress.acknowledgedLines}/${state.progress.totalLines}    错误：${state.progress.errorCount}")
            state.progress.currentLine?.let {
                Text("当前：第 ${it.number} 行 ${it.command}", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.safetyArmed, onCheckedChange = viewModel::setSafetyArmed)
                Text("我已确认护目镜、防火、急停和材料固定")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = viewModel::startJob, modifier = Modifier.weight(1f)) { Text("开始") }
                OutlinedButton(onClick = viewModel::pauseJob, modifier = Modifier.weight(1f)) { Text("暂停") }
                OutlinedButton(onClick = viewModel::resumeJob, modifier = Modifier.weight(1f)) { Text("继续") }
                OutlinedButton(onClick = viewModel::stopJob, modifier = Modifier.weight(1f)) { Text("停止") }
            }
        }

        SectionCard("手动命令") {
            OutlinedTextField(
                value = state.manualCommand,
                onValueChange = viewModel::setManualCommand,
                label = { Text("输入 G-code 或 GRBL 命令") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = viewModel::sendManualCommand, modifier = Modifier.weight(1f)) { Text("发送") }
                OutlinedButton(onClick = { viewModel.sendRealtime("!") }, modifier = Modifier.weight(1f)) { Text("暂停 !") }
                OutlinedButton(onClick = { viewModel.sendRealtime("~") }, modifier = Modifier.weight(1f)) { Text("继续 ~") }
                OutlinedButton(onClick = { viewModel.sendRealtime("?") }, modifier = Modifier.weight(1f)) { Text("状态 ?") }
            }
        }
    }
}

@Composable
private fun LogPage(state: LaserUiState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(state.log) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun PositionText(label: String, position: MachinePosition?) {
    Text("$label：${position?.let { "X${fmt(it.x)} Y${fmt(it.y)} Z${fmt(it.z)}" } ?: "-"}")
}

@Composable
private fun GcodePreview(lines: List<GcodeLine>, bounds: GcodeBounds) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(4.dp))
            .padding(4.dp)
    ) {
        if (!bounds.hasMotion || bounds.width == 0.0 || bounds.height == 0.0) return@Canvas
        val path = Path()
        var initialized = false
        var x = 0.0
        var y = 0.0
        fun mapX(value: Double): Float = ((value - bounds.minX) / bounds.width * size.width).toFloat()
        fun mapY(value: Double): Float = (size.height - ((value - bounds.minY) / bounds.height * size.height)).toFloat()
        lines.take(3000).forEach { line ->
            val cx = valueAfter(line.command, 'X') ?: x
            val cy = valueAfter(line.command, 'Y') ?: y
            if (line.command.startsWith("G0", true) || line.command.startsWith("G1", true)) {
                if (!initialized) {
                    path.moveTo(mapX(cx), mapY(cy))
                    initialized = true
                } else {
                    path.lineTo(mapX(cx), mapY(cy))
                }
                x = cx
                y = cy
            }
        }
        drawPath(path, color = color)
        drawLine(Color.Gray, Offset.Zero, Offset(size.width, 0f))
    }
}

private fun valueAfter(command: String, axis: Char): Double? {
    val index = command.indexOf(axis, ignoreCase = true)
    if (index < 0) return null
    return command
        .drop(index + 1)
        .takeWhile { it.isDigit() || it == '-' || it == '+' || it == '.' }
        .toDoubleOrNull()
}

private fun fmt(value: Double): String = "%.2f".format(value)
