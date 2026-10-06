package com.proximity.finder.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.proximity.finder.model.NearbyDevice
import com.proximity.finder.util.LogBuffer
import java.util.Locale

// ── 颜色常量 ──
private val BgColor = Color(0xFF0A0E17)
private val SurfaceColor = Color(0xFF111827)
private val CardColor = Color(0xFF151D2E)
private val BorderColor = Color(0xFF1E2A42)
private val CyanColor = Color(0xFF00E5FF)
private val PinkColor = Color(0xFFFF3D7F)
private val GreenColor = Color(0xFF00E676)
private val OrangeColor = Color(0xFFFF9F43)
private val TextPrimary = Color(0xFFE8ECF4)
private val TextSecondary = Color(0xFF5A6A8A)
private val TextDim = Color(0xFF3A4A6A)

/**
 * 主界面：根据 currentTab 切换页面
 */
@Composable
fun MainScreen(
    devices: List<NearbyDevice>,
    selectedId: String?,
    currentTab: String,
    nickname: String,
    bleStatus: String,
    logEntries: List<LogBuffer.Entry>,
    searchMode: String,
    lanHintVisible: Boolean = false,
    onToggleSearchMode: (String) -> Unit,
    onDeviceTap: (NearbyDevice) -> Unit,
    onTabChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit
) {
    val hasNear = devices.any { it.isNear }

    // 近距离微震动画
    val infinite = rememberInfiniteTransition(label = "main")
    val shakeX by infinite.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(200), RepeatMode.Reverse),
        label = "shake"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .offset(x = if (hasNear && currentTab == "scan") (shakeX * 0.5f).dp else 0.dp)
    ) {
        // 内容区（权重填满）
        Box(modifier = Modifier.weight(1f)) {
            when (currentTab) {
                "scan" -> ScanContent(devices, selectedId, bleStatus, searchMode, lanHintVisible, onToggleSearchMode, onDeviceTap)
                "profile" -> ProfileContent(nickname, logEntries, onNicknameChange, onClearLogs, onCopyLogs)
            }
        }

        // ── 底部导航栏 ──
        BottomNavBar(currentTab, onTabChange)
    }
}

// ═══════════════════════════════════════
//  扫描页（原主界面）
// ═══════════════════════════════════════
@Composable
private fun ScanContent(
    devices: List<NearbyDevice>,
    selectedId: String?,
    bleStatus: String,
    searchMode: String,
    lanHintVisible: Boolean,
    onToggleSearchMode: (String) -> Unit,
    onDeviceTap: (NearbyDevice) -> Unit
) {
    val selectedDevice = devices.find { it.id == selectedId }
    val nearestDist = devices.minOfOrNull { it.distance } ?: 0f

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── 标题 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Device Searcher", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            if (devices.isNotEmpty()) {
                val summary = if (searchMode == "lan") {
                    "${devices.size} 台设备在线 · UDP 局域网"
                } else {
                    "${devices.size} 台设备在线 · 最近 ${String.format(Locale.US, "%.1f", nearestDist)}m"
                }
                Text(summary, fontSize = 11.sp, color = TextSecondary)
            } else {
                Text(bleStatus, fontSize = 11.sp, color = TextSecondary)
            }
        }

        // ── 三模式切换按钮 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ModeButton(
                label = "BLE",
                subtitle = "近距",
                isActive = searchMode == "ble",
                color = CyanColor,
                modifier = Modifier.weight(1f),
                onClick = { onToggleSearchMode("ble") }
            )
            ModeButton(
                label = "WiFi",
                subtitle = "远距",
                isActive = searchMode == "wifi",
                color = OrangeColor,
                modifier = Modifier.weight(1f),
                onClick = { onToggleSearchMode("wifi") }
            )
            ModeButton(
                label = "局域网",
                subtitle = "UDP",
                isActive = searchMode == "lan",
                color = GreenColor,
                modifier = Modifier.weight(1f),
                onClick = { onToggleSearchMode("lan") }
            )
        }

        // ── 局域网连接提示 ──
        if (lanHintVisible || searchMode == "lan") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GreenColor.copy(alpha = 0.08f))
                    .border(1.dp, GreenColor.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("💡", fontSize = 13.sp)
                Text(
                    "请确保两台设备连接到同一个 WiFi 网络\nUDP 广播将在局域网内自动发现对方",
                    fontSize = 11.sp,
                    color = GreenColor.copy(alpha = 0.85f),
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (devices.isEmpty()) {
            // ── 空状态 ──
            EmptyScanState(bleStatus, searchMode)
        } else {
            // ── 箭头圆环 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .padding(horizontal = 20.dp)
            ) {
                RadarView(
                    devices = devices,
                    selectedId = selectedId,
                    onDeviceTap = onDeviceTap,
                    modifier = Modifier.fillMaxSize(),
                    searchMode = searchMode
                )
            }

            // ── 设备标签组 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                devices.forEach { device ->
                    DeviceTag(device, device.id == selectedId, { onDeviceTap(device) })
                }
            }

            // ── 选中设备详情 ──
            if (selectedDevice != null) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    DeviceDetailCard(selectedDevice)
                }
            }
        }
    }
}

// ── 空状态：扫描等待 ──
@Composable
private fun EmptyScanState(bleStatus: String, searchMode: String = "ble") {
    val infinite = rememberInfiniteTransition(label = "empty")
    val pulseR by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )
    val glowColor = if (searchMode == "wifi") OrangeColor else CyanColor

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 脉冲动画
        Box(
            modifier = Modifier.size(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(180.dp)) {
                val cx = size.width / 2
                val cy = size.height / 2
                // 扩散环
                for (i in 0..2) {
                    val phase = (pulseR + i * 0.33f) % 1f
                    val r = phase * cx
                    val alpha = (1f - phase) * 0.3f
                    drawCircle(glowColor.copy(alpha = alpha), radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style = Stroke(width = 1.5f * density))
                }
                // 中心点
                drawCircle(glowColor.copy(alpha = 0.8f), radius = 6f * density,
                    center = androidx.compose.ui.geometry.Offset(cx, cy))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(bleStatus, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        val hint = if (searchMode == "wifi") {
            "请确保对方也打开了本 App\nWiFi 已开启"
        } else {
            "请确保对方也打开了本 App\n蓝牙已开启"
        }
        Text(
            hint,
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

// ═══════════════════════════════════════
//  我的页
// ═══════════════════════════════════════
@Composable
private fun ProfileContent(
    nickname: String,
    logEntries: List<LogBuffer.Entry>,
    onNicknameChange: (String) -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf(nickname) }
    var showLogs by remember { mutableStateOf(false) }
    val logListState = rememberLazyListState()

    // 日志更新时自动滚动到底部
    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) {
            logListState.animateScrollToItem(logEntries.size - 1)
        }
    }

    // 每次打开弹窗时同步当前昵称
    LaunchedEffect(showEditDialog) {
        if (showEditDialog) inputText = nickname
    }

    // ── 昵称编辑弹窗 ──
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = CardColor,
            title = {
                Text("设置昵称", color = TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("其他设备将看到这个名字", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { if (it.length <= 12) inputText = it },
                        singleLine = true,
                        placeholder = { Text("输入昵称（最多12字）", color = TextDim) },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanColor,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = CyanColor,
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Text(
                    "确定",
                    color = if (inputText.isNotBlank()) CyanColor else TextDim,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        if (inputText.isNotBlank()) {
                            onNicknameChange(inputText.trim())
                            showEditDialog = false
                        }
                    }
                )
            },
            dismissButton = {
                Text("取消", color = TextSecondary,
                    modifier = Modifier.clickable { showEditDialog = false })
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("我的", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("设备信息与设置", fontSize = 12.sp, color = TextSecondary)

        Spacer(modifier = Modifier.height(40.dp))

        // 设备信息卡（可点击设置昵称）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardColor)
                .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                .clickable { showEditDialog = true }
                .padding(20.dp)
        ) {
            // 头像
            val avatarLetter = if (nickname.isNotEmpty()) nickname.first().toString() else "?"
            val avatarAlpha = if (nickname.isNotEmpty()) 0.3f else 0.1f
            val borderAlpha = if (nickname.isNotEmpty()) 0.7f else 0.4f

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(CyanColor.copy(alpha = avatarAlpha))
                    .border(2.dp, CyanColor.copy(alpha = borderAlpha), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    avatarLetter,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanColor.copy(alpha = if (nickname.isNotEmpty()) 1f else 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (nickname.isNotEmpty()) {
                Text(nickname, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("点击修改昵称", fontSize = 12.sp, color = TextSecondary)
            } else {
                Text("未设置昵称", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("点击设置你的设备名称", fontSize = 12.sp, color = CyanColor.copy(alpha = 0.7f))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 设置项
        val settings = listOf(
            "蓝牙状态" to "已开启",
            "广播状态" to "广播中",
            "扫描模式" to "低延迟",
            "震动反馈" to "开启",
        )
        settings.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .border(
                        width = 0.5.dp,
                        color = BorderColor.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(0.dp)
                    )
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontSize = 14.sp, color = TextPrimary)
                Text(value, fontSize = 13.sp, color = CyanColor, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── 日志查看器入口 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardColor)
                .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                .clickable { showLogs = !showLogs }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("查看运行日志", fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
            Text(
                if (showLogs) "收起 ▲" else "展开 ▼",
                fontSize = 12.sp, color = CyanColor, fontWeight = FontWeight.SemiBold
            )
        }

        // ── 日志列表 ──
        if (showLogs) {
            Spacer(modifier = Modifier.height(8.dp))

            // 工具栏：条数 + 复制 + 清除按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${logEntries.size} 条日志",
                    fontSize = 11.sp, color = TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "复制全部",
                        fontSize = 12.sp,
                        color = if (logEntries.isNotEmpty()) CyanColor else TextDim,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            if (logEntries.isNotEmpty()) onCopyLogs()
                        }
                    )
                    Text(
                        "清除",
                        fontSize = 12.sp,
                        color = if (logEntries.isNotEmpty()) PinkColor else TextDim,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            if (logEntries.isNotEmpty()) onClearLogs()
                        }
                    )
                }
            }

            // 日志滚动列表
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceColor)
                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
            ) {
                if (logEntries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无日志", fontSize = 13.sp, color = TextDim)
                    }
                } else {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(logEntries, key = { "${it.time}_${it.hashCode()}" }) { entry ->
                            LogEntryRow(entry)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Text("Powered by Rolling", fontSize = 11.sp, color = TextDim, fontWeight = FontWeight.Light)
        Spacer(modifier = Modifier.height(2.dp))
        Text("Device Searcher v2.0", fontSize = 11.sp, color = TextDim)
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ── 单条日志行 ──
@Composable
private fun LogEntryRow(entry: LogBuffer.Entry) {
    val levelColor = when (entry.level) {
        LogBuffer.Level.DEBUG -> TextDim
        LogBuffer.Level.INFO -> TextSecondary
        LogBuffer.Level.WARN -> OrangeColor
        LogBuffer.Level.ERROR -> PinkColor
    }
    val levelTag = when (entry.level) {
        LogBuffer.Level.DEBUG -> "D"
        LogBuffer.Level.INFO -> "I"
        LogBuffer.Level.WARN -> "W"
        LogBuffer.Level.ERROR -> "E"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            entry.time,
            fontSize = 9.sp,
            color = TextDim,
            modifier = Modifier.width(68.dp)
        )
        Text(
            levelTag,
            fontSize = 9.sp,
            color = levelColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(12.dp)
        )
        Text(
            "${entry.tag}: ${entry.message}",
            fontSize = 9.sp,
            color = levelColor.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f)
        )
    }
}

// ═══════════════════════════════════════
//  共用子组件
// ═══════════════════════════════════════

@Composable
private fun DeviceTag(
    device: NearbyDevice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isWifi = device.source == "wifi"
    val isLan = device.source == "lan"
    val borderColor = when {
        isLan -> GreenColor.copy(alpha = 0.4f)
        isWifi -> OrangeColor.copy(alpha = 0.4f)
        else -> BorderColor
    }
    val dotColor = when {
        isLan -> GreenColor
        isWifi -> OrangeColor
        else -> device.color
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (isSelected) device.color.copy(alpha = 0.12f) else CardColor)
            .border(
                1.5.dp,
                if (isSelected) device.color.copy(alpha = 0.5f) else borderColor,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            device.name,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) device.color else TextPrimary
        )
        if (isLan) {
            Text("L", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = GreenColor)
        } else if (isWifi) {
            Text("W", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = OrangeColor)
        }
    }
}

@Composable
private fun DeviceDetailCard(device: NearbyDevice) {
    val intensity = device.intensity

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        device.color.copy(alpha = 0.05f),
                        device.color.copy(alpha = 0.12f)
                    )
                )
            )
            .border(1.dp, device.color.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(device.color.copy(alpha = 0.08f + intensity * 0.35f))
                    .border(2.dp, device.color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(device.avatar, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = device.color)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                val subtitle = when {
                    device.source == "lan" -> "🌐 局域网 · UDP 广播"
                    device.source == "wifi" -> {
                        when {
                            device.distance <= 15f -> "📶 WiFi · 近距离"
                            device.distance <= 40f -> "📶 WiFi · 中等距离"
                            else -> "📶 WiFi · 远距离"
                        }
                    }
                    device.isNear -> "📱 BLE · 已接近！"
                    else -> "📱 BLE · 连接稳定"
                }
                Text(
                    subtitle,
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    color = when (device.source) {
                        "lan" -> GreenColor
                        "wifi" -> OrangeColor
                        else -> if (device.isNear) GreenColor else TextSecondary
                    }
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (device.source == "lan") {
                    // LAN 模式：信号强度 + 圆圈可视化
                    val tierLabel = when {
                        device.rssi == Int.MIN_VALUE || device.rssi >= 0 -> "?"
                        device.rssi > -50 -> "强"
                        device.rssi > -70 -> "中"
                        else -> "弱"
                    }
                    val tierColor = when (tierLabel) {
                        "强" -> GreenColor
                        "中" -> OrangeColor
                        "弱" -> PinkColor
                        else -> TextSecondary
                    }
                    // 信号圆圈大小
                    val circleSize = (device.intensity * 24 + 10).dp
                    Box(
                        modifier = Modifier.size(circleSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = tierColor.copy(alpha = 0.2f),
                                radius = size.minDimension / 2
                            )
                            drawCircle(
                                color = tierColor,
                                radius = size.minDimension / 2,
                                style = Stroke(width = 2f * density)
                            )
                        }
                    }
                    Text(tierLabel, fontSize = 20.sp, fontWeight = FontWeight.Black, color = tierColor)
                    Text("信号", fontSize = 10.sp, color = TextSecondary)
                } else if (device.source == "wifi") {
                    // 三档文字
                    val tierLabel = when {
                        device.distance <= 15f -> "近"
                        device.distance <= 40f -> "中"
                        else -> "远"
                    }
                    val tierColor = when {
                        device.distance <= 15f -> GreenColor
                        device.distance <= 40f -> OrangeColor
                        else -> PinkColor
                    }
                    Text(tierLabel, fontSize = 24.sp, fontWeight = FontWeight.Black, color = tierColor)
                    Text("约${String.format(Locale.US, "%.0f", device.distance)}m", fontSize = 10.sp, color = TextSecondary)
                } else {
                    Text(
                        String.format(Locale.US, "%.1f", device.distance),
                        fontSize = 30.sp, fontWeight = FontWeight.Black, color = device.color,
                        letterSpacing = (-1).sp
                    )
                    Text("米", fontSize = 10.sp, color = TextSecondary)
                }
            }
        }

        if (device.isNear) {
            Spacer(modifier = Modifier.height(12.dp))
            VibrationIndicator(device.distance)
        }
    }
}

@Composable
private fun VibrationIndicator(distance: Float) {
    val vibIntensity = (1f - distance.coerceIn(0f, 10f) / 10f)
    val bars = (vibIntensity * 5).toInt().coerceIn(0, 5)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PinkColor.copy(alpha = 0.04f))
            .border(1.dp, PinkColor.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("📳", fontSize = 16.sp)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f)
        ) {
            for (i in 1..5) {
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height((4 + i * 4).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (i <= bars) Brush.verticalGradient(listOf(PinkColor, OrangeColor))
                            else Brush.verticalGradient(listOf(BorderColor, BorderColor))
                        )
                )
            }
        }
        Text(
            if (bars > 0) "震动反馈 ${String.format(Locale.US, "%.0f", vibIntensity * 100)}%" else "未触发",
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (bars > 0) PinkColor else TextDim
        )
    }
}

// ── 模式切换按钮 ──
@Composable
private fun ModeButton(
    label: String,
    subtitle: String,
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) color.copy(alpha = 0.15f) else CardColor)
            .border(
                1.5.dp,
                if (isActive) color.copy(alpha = 0.6f) else BorderColor,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (isActive) color else TextDim)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isActive) color else TextSecondary
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            subtitle,
            fontSize = 9.sp,
            color = if (isActive) color.copy(alpha = 0.6f) else TextDim
        )
    }
}

// ── 底部导航栏（带 Tab 切换）──
@Composable
private fun BottomNavBar(currentTab: String, onTabChange: (String) -> Unit) {
    val tabs = listOf(
        Triple("scan", "指示", true),
        Triple("profile", "我的", false),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceColor.copy(alpha = 0.93f))
                .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { (id, label, _) ->
                val active = currentTab == id
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (active) CyanColor.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { onTabChange(id) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.size(20.dp)) {
                            val r = 7f * density
                            val cx = size.width / 2
                            val cy = size.height / 2
                            drawCircle(
                                color = if (active) CyanColor else TextSecondary,
                                radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy),
                                style = Stroke(width = 1.5f * density)
                            )
                            if (active) {
                                drawCircle(CyanColor, radius = 2.5f * density,
                                    center = androidx.compose.ui.geometry.Offset(cx, cy))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = if (active) CyanColor else TextSecondary
                    )
                }
            }
        }
    }
}
