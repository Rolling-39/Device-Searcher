package com.proximity.finder

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.proximity.finder.ble.BleProximityManager
import com.proximity.finder.lan.LanDiscoveryManager
import com.proximity.finder.model.NearbyDevice
import com.proximity.finder.ui.MainScreen
import com.proximity.finder.util.HapticUtil
import com.proximity.finder.util.LogBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        internal const val PREFS_NAME = "proximity_prefs"
        internal const val KEY_NICKNAME = "nickname"
    }

    private var bleManager: BleProximityManager? = null
    private var wifiManager: com.proximity.finder.wifi.WifiProximityManager? = null
    private var lanManager: LanDiscoveryManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bleManager = BleProximityManager(this)
        wifiManager = com.proximity.finder.wifi.WifiProximityManager(this)
        lanManager = LanDiscoveryManager(this)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedNickname = prefs.getString(KEY_NICKNAME, "") ?: ""
        bleManager?.nickname = savedNickname
        wifiManager?.nickname = savedNickname
        lanManager?.nickname = savedNickname

        setContent {
            ProximityApp(bleManager!!, wifiManager!!, lanManager!!, savedNickname)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager?.release()
        wifiManager?.stop()
        lanManager?.stop()
    }
}

private fun getRequiredPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}

private fun isBluetoothEnabled(context: Context): Boolean {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return bm?.adapter?.isEnabled == true
}

private fun isWifiEnabled(context: Context): Boolean {
    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    return wm?.isWifiEnabled == true
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ProximityApp(
    bleManager: BleProximityManager,
    wifiManager: com.proximity.finder.wifi.WifiProximityManager,
    lanManager: LanDiscoveryManager,
    initialNickname: String
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    val TAG = "MainActivity"

    // ── 权限管理 ──
    var bleStarted by remember { mutableStateOf(false) }
    val permissionsState = rememberMultiplePermissionsState(
        permissions = getRequiredPermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        Log.i(TAG, "权限回调: allGranted=$allGranted")
        if (allGranted && !bleStarted) {
            bleManager.startAll()
            bleStarted = true
        }
    }

    // ── 搜索模式：ble / wifi / lan ──
    var searchMode by remember { mutableStateOf("ble") }

    // ── 设备数据 ──
    var bleDevices by remember { mutableStateOf<List<NearbyDevice>>(emptyList()) }
    var wifiDevices by remember { mutableStateOf<List<NearbyDevice>>(emptyList()) }
    var lanDevices by remember { mutableStateOf<List<NearbyDevice>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var currentTab by remember { mutableStateOf("scan") }
    var nickname by remember { mutableStateOf(initialNickname) }
    var bleStatus by remember { mutableStateOf("初始化中...") }
    var lanHintVisible by remember { mutableStateOf(false) }

    // 根据搜索模式决定显示哪个列表
    val displayDevices = when (searchMode) {
        "wifi" -> wifiDevices
        "lan" -> lanDevices
        else -> bleDevices
    }

    // ── 日志数据 ──
    var logEntries by remember { mutableStateOf<List<LogBuffer.Entry>>(emptyList()) }
    LaunchedEffect(Unit) {
        LogBuffer.entries.collect { logEntries = it }
    }

    // ── 昵称变更：持久化 + 更新所有管理器 ──
    fun updateNickname(newName: String) {
        nickname = newName
        bleManager.nickname = newName
        wifiManager.nickname = newName
        lanManager.nickname = newName
        prefs.edit().putString(MainActivity.KEY_NICKNAME, newName).apply()
        Log.i(TAG, "昵称已更新并持久化: $newName")
    }

    // ── 切换搜索模式 ──
    fun toggleSearchMode(mode: String) {
        if (mode == searchMode) return
        Log.i(TAG, "切换搜索模式: $searchMode → $mode")

        // 先停掉当前模式
        when (searchMode) {
            "ble" -> { bleManager.stopAll(); bleStarted = false }
            "wifi" -> wifiManager.stop()
            "lan" -> lanManager.stop()
        }

        lanHintVisible = false

        when (mode) {
            "ble" -> {
                if (!isBluetoothEnabled(context)) {
                    bleStatus = "蓝牙未开启，请打开蓝牙"
                    LogBuffer.add("App", "切换BLE失败：蓝牙未开启", LogBuffer.Level.WARN)
                    return
                }
                bleStatus = "扫描中..."
                bleManager.startAll()
                bleStarted = true
                searchMode = "ble"
            }
            "wifi" -> {
                if (!isWifiEnabled(context)) {
                    bleStatus = "WiFi 未开启，请打开 WiFi"
                    LogBuffer.add("App", "切换WiFi Direct失败：WiFi未开启", LogBuffer.Level.WARN)
                    return
                }
                bleStatus = "WiFi Direct 搜索中..."
                wifiManager.start()
                searchMode = "wifi"
            }
            "lan" -> {
                if (!isWifiEnabled(context)) {
                    bleStatus = "WiFi 未开启，请打开 WiFi"
                    LogBuffer.add("App", "切换局域网失败：WiFi未开启", LogBuffer.Level.WARN)
                    return
                }
                if (!lanManager.hasActiveNetwork()) {
                    bleStatus = "未连接 WiFi 或热点，请先联网"
                    lanHintVisible = true
                    LogBuffer.add("App", "切换局域网提示：无可用网络接口", LogBuffer.Level.WARN)
                }
                bleStatus = "局域网搜索中..."
                lanManager.start()
                searchMode = "lan"
            }
        }
    }

    // ── BLE 扫描数据监听 ──
    LaunchedEffect(Unit) {
        bleManager.discoveredDevices.collect { nearbyDevices ->
            bleDevices = nearbyDevices
        }
    }

    // ── WiFi 设备监听 ──
    LaunchedEffect(Unit) {
        wifiManager.wifiDevices.collect { devices ->
            wifiDevices = devices
        }
    }

    // ── 局域网设备监听 ──
    LaunchedEffect(Unit) {
        lanManager.devices.collect { devices ->
            lanDevices = devices
        }
    }

    // ── 权限检查 + BLE 初始启动 ──
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        Log.i(TAG, "权限状态: allGranted=${permissionsState.allPermissionsGranted}")
        if (permissionsState.allPermissionsGranted) {
            if (!isBluetoothEnabled(context)) {
                bleStatus = "蓝牙未开启，请打开蓝牙"
                return@LaunchedEffect
            }
            bleStatus = "扫描中..."
            if (!bleStarted) {
                bleManager.startAll()
                bleStarted = true
            }
        } else {
            bleStatus = "需要蓝牙和位置权限"
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    // ── 近距离连续震动：距离越近频率越快、强度越大 ──
    val nearDevices = displayDevices.filter { it.isNear }
    val minDist = nearDevices.minOfOrNull { it.distance }

    LaunchedEffect(minDist) {
        if (minDist == null) return@LaunchedEffect
        while (isActive) {
            HapticUtil.vibrateNear(context, minDist)
            // 距离越近间隔越短：1m≈300ms, 5m≈1200ms, 10m≈3000ms
            val cooldown = (minDist * 200f).coerceIn(250f, 3000f).toLong()
            delay(cooldown)
        }
    }

    // ── 状态描述 ──
    val statusText = when {
        searchMode == "wifi" -> {
            if (wifiDevices.isNotEmpty()) "${wifiDevices.size}台WiFi设备在线"
            else "WiFi Direct 搜索中..."
        }
        searchMode == "lan" -> {
            if (lanDevices.isNotEmpty()) "${lanDevices.size}台局域网设备在线"
            else "局域网搜索中..."
        }
        displayDevices.isNotEmpty() -> {
            "${displayDevices.size}台BLE设备在线 · 最近 ${
                String.format(Locale.US, "%.1f", displayDevices.minOf { it.distance })
            }m"
        }
        else -> bleStatus
    }

    // ── UI ──
    MainScreen(
        devices = displayDevices,
        selectedId = selectedId,
        currentTab = currentTab,
        nickname = nickname,
        bleStatus = statusText,
        logEntries = logEntries,
        searchMode = searchMode,
        lanHintVisible = lanHintVisible,
        onToggleSearchMode = { mode -> toggleSearchMode(mode) },
        onDeviceTap = { device -> selectedId = device.id },
        onTabChange = { tab -> currentTab = tab },
        onNicknameChange = { name -> updateNickname(name) },
        onClearLogs = { LogBuffer.clear() },
        onCopyLogs = {
            val text = logEntries.joinToString("\n") { e ->
                "[${e.time}] [${e.level.name}] ${e.tag}: ${e.message}"
            }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Device Searcher 日志", text))
            Log.i(TAG, "日志已复制到剪贴板 (${logEntries.size} 条)")
        }
    )
}
