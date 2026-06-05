package com.x.lasergrbl_mobile

import android.content.Intent
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.x.lasergrbl_mobile.app.LaserUiState
import com.x.lasergrbl_mobile.app.LaserViewModel
import com.x.lasergrbl_mobile.app.ThemeMode
import com.x.lasergrbl_mobile.core.GcodeBounds
import com.x.lasergrbl_mobile.core.GcodeLine
import com.x.lasergrbl_mobile.core.GcodeParser
import com.x.lasergrbl_mobile.core.MachinePosition
import com.x.lasergrbl_mobile.ui.theme.LaserGRBLMobileTheme

class MainActivity : ComponentActivity() {
    private val viewModel: LaserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        setContent {
            val state by viewModel.uiState.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (state.themeMode) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            LaserGRBLMobileTheme(darkTheme = darkTheme) {
                LaserApp(
                    state = state,
                    viewModel = viewModel,
                    openUrl = ::openUrl,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val type = intent.type.orEmpty().lowercase()
        val path = uri.toString().lowercase()
        when {
            type == "image/svg+xml" || path.endsWith(".svg") -> viewModel.loadSvgAsGcode(uri)
            type.startsWith("image/") -> viewModel.loadImageAsGcode(uri)
            else -> viewModel.loadGcode(uri)
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}

@Composable
private fun LaserApp(
    state: LaserUiState,
    viewModel: LaserViewModel,
    openUrl: (String) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("连接", "控制", "文件", "发送", "设置", "日志", "关于")

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
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
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
                4 -> SettingsPage(state, viewModel)
                5 -> LogPage(state)
                else -> AboutPage(openUrl)
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
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
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
            OutlinedTextField(
                value = state.baudRateInput,
                onValueChange = viewModel::setBaudRateInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("波特率") },
                supportingText = { Text("推荐 115200；特殊固件可手动调整") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            if (state.serial.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.serial.error, color = MaterialTheme.colorScheme.tertiary)
            }
        }

        SectionCard("连接提示") {
            Text("1. 手机需要支持 USB OTG。")
            Text("2. 常见主控使用 CH340、CP2102、FTDI 或 CDC 串口。")
            Text("3. 默认波特率 115200，适配大多数 GRBL 固件。")
            Text("4. 如果设备无响应，可以确认固件配置后尝试 9600、57600 或 230400。")
            Text("5. 开始加工前务必戴护目镜并确认急停有效。")
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
            listOf(
                listOf(0.01, 0.05, 0.1),
                listOf(1.0, 10.0, 50.0),
            ).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { step ->
                        StepButton(
                            label = "${fmtStep(step)}mm",
                            selected = state.jogStep == step,
                            onClick = { viewModel.setJogStep(step) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            AxisJogRow(axis = 'X', negativeLabel = "X-", positiveLabel = "X+", viewModel = viewModel)
            AxisJogRow(axis = 'Y', negativeLabel = "Y-", positiveLabel = "Y+", viewModel = viewModel)
            AxisJogRow(axis = 'Z', negativeLabel = "Z-", positiveLabel = "Z+", viewModel = viewModel)
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
private fun StepButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun AxisJogRow(
    axis: Char,
    negativeLabel: String,
    positiveLabel: String,
    viewModel: LaserViewModel,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(onClick = { viewModel.jog(axis, -1) }, modifier = Modifier.weight(1f)) {
            Text(negativeLabel, maxLines = 1)
        }
        Text(
            "$axis 轴",
            modifier = Modifier.weight(0.7f),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Button(onClick = { viewModel.jog(axis, 1) }, modifier = Modifier.weight(1f)) {
            Text(positiveLabel, maxLines = 1)
        }
    }
}

@Composable
private fun FilePage(state: LaserUiState, viewModel: LaserViewModel) {
    val gcodeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.loadGcode(uri)
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.loadImageAsGcode(uri)
    }
    val svgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.loadSvgAsGcode(uri)
    }

    PageColumn {
        SectionCard("载入任务") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { gcodeLauncher.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("选择 G-code")
                }
                Button(
                    onClick = { imageLauncher.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("选择图片转换")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { svgLauncher.launch(arrayOf("image/svg+xml", "text/xml", "text/*", "*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("选择 SVG 转换")
                }
                OutlinedButton(
                    onClick = { },
                    enabled = false,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("材料预设待加入")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("文件：${state.job.fileName}")
            Text("来源：${state.job.source}")
            Text("有效行数：${state.job.lines.size}")
            state.job.note?.let { Text(it) }
            state.job.bounds?.let { bounds ->
                if (bounds.hasMotion) {
                    Text("范围：X ${fmt(bounds.minX)} ~ ${fmt(bounds.maxX)}，Y ${fmt(bounds.minY)} ~ ${fmt(bounds.maxY)}")
                    Text("尺寸：${fmt(bounds.width)} x ${fmt(bounds.height)} mm")
                    GcodePreview(state.job.lines, bounds)
                }
            }
            if (state.job.error != null) Text(state.job.error, color = MaterialTheme.colorScheme.tertiary)
        }

        SectionCard("转换参数") {
            Text("宽度：${fmt(state.imageWidthMm)} mm")
            Slider(
                value = state.imageWidthMm.toFloat(),
                onValueChange = { viewModel.setImageWidthMm(it.toDouble()) },
                valueRange = 5f..200f,
            )
            Text("行距：${fmt(state.imageLineStepMm)} mm")
            Slider(
                value = state.imageLineStepMm.toFloat(),
                onValueChange = { viewModel.setImageLineStepMm(it.toDouble()) },
                valueRange = 0.05f..0.5f,
            )
            Text("雕刻进给：F${state.imageFeedRate}    空程：F${state.imageTravelRate}")
            Slider(
                value = state.imageFeedRate.toFloat(),
                onValueChange = { viewModel.setImageFeedRate(it.toInt()) },
                valueRange = 100f..6000f,
            )
            Text("最大功率：S${state.imageMaxPower}    阈值：${state.imageThreshold}")
            Slider(
                value = state.imageMaxPower.toFloat(),
                onValueChange = { viewModel.setImageMaxPower(it.toInt()) },
                valueRange = 1f..1000f,
            )
            Slider(
                value = state.imageThreshold.toFloat(),
                onValueChange = { viewModel.setImageThreshold(it.toInt()) },
                valueRange = 0f..255f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.imageBidirectional, onCheckedChange = viewModel::setImageBidirectional)
                Text("双向扫描")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.imageInvert, onCheckedChange = viewModel::setImageInvert)
                Text("反相雕刻")
            }
            Text("图片会生成 M4 动态功率扫描线；SVG 会把 M/L/H/V/Z 线段转换为矢量雕刻路径。第一次实测建议降低功率并空跑。")
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
private fun SettingsPage(state: LaserUiState, viewModel: LaserViewModel) {
    PageColumn {
        SectionCard("主题") {
            ThemeOption(
                title = "系统",
                description = "跟随手机系统深色或浅色主题",
                selected = state.themeMode == ThemeMode.System,
                onClick = { viewModel.setThemeMode(ThemeMode.System) },
            )
            ThemeOption(
                title = "浅色",
                description = "固定使用浅色主题",
                selected = state.themeMode == ThemeMode.Light,
                onClick = { viewModel.setThemeMode(ThemeMode.Light) },
            )
            ThemeOption(
                title = "深色",
                description = "固定使用深色主题",
                selected = state.themeMode == ThemeMode.Dark,
                onClick = { viewModel.setThemeMode(ThemeMode.Dark) },
            )
        }
    }
}

@Composable
private fun ThemeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
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
private fun AboutPage(openUrl: (String) -> Unit) {
    PageColumn {
        SectionCard("LaserGRBL Mobile") {
            Text("Android 手机端 GRBL / 激光雕刻机上位机。")
            Text("用于 USB OTG 串口连接、手动控制、G-code 读取、图片 / SVG 转换和任务发送。")
        }

        SectionCard("项目链接") {
            LinkButton(
                title = "仓库",
                url = "https://github.com/ra1nyxin/LaserGRBL-Mobile",
                openUrl = openUrl,
            )
            LinkButton(
                title = "Issues",
                url = "https://github.com/ra1nyxin/LaserGRBL-Mobile/issues",
                openUrl = openUrl,
            )
            LinkButton(
                title = "Pull Requests",
                url = "https://github.com/ra1nyxin/LaserGRBL-Mobile/pulls",
                openUrl = openUrl,
            )
            LinkButton(
                title = "Releases / APK",
                url = "https://github.com/ra1nyxin/LaserGRBL-Mobile/releases",
                openUrl = openUrl,
            )
            LinkButton(
                title = "开源协议 Apache-2.0",
                url = "https://github.com/ra1nyxin/LaserGRBL-Mobile/blob/master/LICENSE",
                openUrl = openUrl,
            )
        }
    }
}

@Composable
private fun LinkButton(
    title: String,
    url: String,
    openUrl: (String) -> Unit,
) {
    OutlinedButton(
        onClick = { openUrl(url) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
            if (GcodeParser.isLinearMotion(line.command)) {
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

private fun fmtStep(value: Double): String {
    return if (value >= 1.0) "%.0f".format(value) else "%.2f".format(value).trimEnd('0')
}
