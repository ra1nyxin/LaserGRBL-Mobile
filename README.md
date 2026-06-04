# LaserGRBL Mobile

LaserGRBL Mobile 是一个面向 Android 手机的 GRBL / 激光雕刻机上位机。目标是让手机通过 USB OTG 串口连接 GRBL 主控，完成状态监控、手动移动、G-code 文件读取、轨迹预览和稳定流式发送。

> 当前阶段没有实机硬件验证，已经完成核心逻辑单元测试和 Debug APK 构建。第一次实机测试前，请务必低功率、空载或不装激光头验证串口与移动控制。

## 功能

- Android 10+ 到 Android 16，`minSdk 29`，`targetSdk 36`
- 中文界面，紧凑布局
- 跟随系统浅色 / 深色主题
- USB OTG 串口连接，支持常见 CH340 / CP2102 / FTDI / CDC 设备
- GRBL 状态解析：`Idle`、`Run`、`Hold`、`Alarm`、坐标、进给、激光/主轴值
- 常用控制：查询、解锁、回零、复位、暂停、继续、关光
- Jog 手动移动，支持步长选择
- 弱光测试，带安全确认提示
- G-code 文件读取，清理注释，估算 XY 范围
- 简易轨迹预览
- 按 `ok/error/ALARM` 单行流式发送，优先稳定
- 发送进度、错误数、当前行、日志
- 单元测试覆盖 G-code 解析、GRBL 状态解析和基础发送流控

## 安全提示

- 激光雕刻机有起火和伤眼风险。
- 首次测试建议拔掉激光头或使用最低功率。
- 开始任务前确认护目镜、防火、急停、材料固定。
- 手机断连、OTG 松动、主控复位都可能导致任务中断。

## 构建

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Debug APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 依赖

- Jetpack Compose / Material 3
- Kotlin
- usb-serial-for-android
- kotlinx-coroutines

## 后续计划

- 图片 / SVG 转 G-code
- 更完整的 GRBL buffer character-counting streaming
- 材料参数库
- 加工前边界框预跑
- 蓝牙 / Wi-Fi 串口桥支持
