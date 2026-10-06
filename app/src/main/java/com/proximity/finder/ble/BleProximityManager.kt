package com.proximity.finder.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.proximity.finder.model.NearbyDevice
import com.proximity.finder.util.LogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

class BleProximityManager(private val context: Context) {

    companion object {
        val SERVICE_UUID: ParcelUuid =
            ParcelUuid.fromString("0000FEED-0000-1000-8000-00805F9B34FB")
        private const val TX_POWER_AT_1M = -59
        private const val PATH_LOSS_EXPONENT = 2.5f
        private const val DEVICE_TIMEOUT_MS = 15_000L
        private const val CLEANUP_INTERVAL_MS = 5_000L

        /** BLE 子系统完全复用周期：每 30 秒重启一次广播+扫描 */
        private const val CYCLE_REFRESH_MS = 30_000L
        /** 设备未找到时递进延迟重试 (秒) */
        private val RETRY_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 12_000L, 20_000L)
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val _discoveredDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<NearbyDevice>> = _discoveredDevices.asStateFlow()

    private data class DeviceEntry(
        val scanResult: ScanResult,
        var lastSeen: Long,
        val rssiHistory: MutableList<Int> = mutableListOf()
    )
    private val deviceMap = java.util.concurrent.ConcurrentHashMap<String, DeviceEntry>()

    private var retryIndex = 0
    private var isRunning = false
    private var cycleCount = 0

    /** BLE 一直找不到设备时的回调（用于触发 WiFi 回退）*/
    var onNoDevicesTimeout: (() -> Unit)? = null

    var nickname: String = ""
        set(value) {
            field = value
            if (advertiser != null) {
                stopAdvertising()
                startAdvertising()
                log("昵称更新，重启广播: $value")
            }
        }

    private fun log(msg: String, level: LogBuffer.Level = LogBuffer.Level.INFO) {
        LogBuffer.add("BLE", msg, level)
    }

    // ═══════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════

    @SuppressLint("MissingPermission")
    fun startAll() {
        if (isRunning) {
            log("BLE 已在运行，忽略重复启动", LogBuffer.Level.WARN)
            return
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            log("蓝牙不可用，无法启动", LogBuffer.Level.ERROR); return
        }
        isRunning = true
        retryIndex = 0
        cycleCount = 0
        log("========== BLE 子系统启动 ==========")

        // 获取全新实例
        refreshBleInstances()
        startAdvertising()
        handler.postDelayed({ startScanning() }, 600L)

        // 2.6 秒后开始第一次重试检查
        handler.postDelayed(retryScanRunnable, 2_600L)

        // 定时清理过期设备
        handler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)

        // 定时刷新 BLE 子系统
        handler.postDelayed(cycleRefreshRunnable, CYCLE_REFRESH_MS)
    }

    fun stopAll() {
        isRunning = false
        stopAdvertising()
        stopScanningInternal()
        handler.removeCallbacks(cleanupRunnable)
        handler.removeCallbacks(cycleRefreshRunnable)
        handler.removeCallbacks(retryScanRunnable)
        log("BLE 子系统已停止")
    }

    /** 获取全新的 Scanner 和 Advertiser 实例 */
    @SuppressLint("MissingPermission")
    private fun refreshBleInstances() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (advertiser == null) log("Advertiser 为 null", LogBuffer.Level.WARN)
        if (scanner == null) log("Scanner 为 null", LogBuffer.Level.WARN)
    }

    // ── 定时清理过期设备 ──
    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val now = System.currentTimeMillis()
            val removed = mutableListOf<String>()
            deviceMap.entries.forEach { (addr, entry) ->
                if (now - entry.lastSeen > DEVICE_TIMEOUT_MS) removed.add(addr)
            }
            if (removed.isNotEmpty()) {
                removed.forEach { deviceMap.remove(it) }
                log("清理过期: ${removed.size}台", LogBuffer.Level.DEBUG)
                publishDevices()
            }
            handler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }

    // ── 定期完全复用 BLE 子系统 ──
    private val cycleRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            cycleCount++
            log("🔄 BLE 周期刷新 #$cycleCount：重启广播+扫描")
            stopScanningInternal()
            stopAdvertising()
            refreshBleInstances()
            startAdvertising()
            // 取消旧重试，但不重置 retryIndex（让重试链跨周期延续）
            handler.removeCallbacks(retryScanRunnable)
            handler.postDelayed({
                stopScanningInternal()
                startScanning()
                // 2.6 秒后继续重试检查
                handler.postDelayed(retryScanRunnable, 2_600L)
            }, 800L)
            handler.postDelayed(this, CYCLE_REFRESH_MS)
        }
    }

    // ── 扫描重试（设备未找到时递进延迟）──
    private val retryScanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (deviceMap.isEmpty() && retryIndex < RETRY_BACKOFF_MS.size) {
                val delay = RETRY_BACKOFF_MS[retryIndex]
                retryIndex++
                log("⚠ 暂无设备，第${retryIndex}次重试扫描 (${delay}ms后)")
                // 完全停止当前扫描，等延迟后再启动
                stopScanningInternal()
                handler.postDelayed({
                    if (isRunning) startScanInternal()
                }, delay)

                if (retryIndex < RETRY_BACKOFF_MS.size) {
                    // 安排下一次检查：本次延迟 + 下次延迟
                    handler.postDelayed(this, delay + RETRY_BACKOFF_MS[retryIndex])
                } else {
                    log("已达最大重试次数，维持当前扫描", LogBuffer.Level.WARN)
                    // 当前确实没有任何设备时，触发 WiFi 回退
                    if (deviceMap.isEmpty()) {
                        log("BLE 重试耗尽且无设备，触发 WiFi 回退")
                        onNoDevicesTimeout?.invoke()
                    }
                }
            } else if (deviceMap.isNotEmpty()) {
                retryIndex = 0 // 有设备了，重置
            }
        }
    }

    // ═══════════════════════════════════════
    //  广播
    // ═══════════════════════════════════════

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            log("✓ 广播启动成功 (tx=${settingsInEffect?.txPowerLevel})")
        }
        override fun onStartFailure(errorCode: Int) {
            val msg = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "数据太大"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "广播实例过多"
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "已启动"
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "内部错误"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "不支持"
                else -> "未知($errorCode)"
            }
            log("✗ 广播失败: $msg", LogBuffer.Level.ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            log("蓝牙不可用", LogBuffer.Level.ERROR); return
        }
        val adv = advertiser ?: run {
            log("Advertiser为null", LogBuffer.Level.ERROR); return
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            log("不支持多广播", LogBuffer.Level.WARN)
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(SERVICE_UUID)

        val scanRespBuilder = AdvertiseData.Builder().setIncludeDeviceName(false)
        if (nickname.isNotBlank()) {
            scanRespBuilder.setIncludeDeviceName(true)
            try { bluetoothAdapter.name = nickname } catch (_: Exception) {}
            log("广播携带昵称: $nickname")
        }

        try {
            adv.startAdvertising(settings, dataBuilder.build(), scanRespBuilder.build(), advertiseCallback)
            log("启动广播 UUID=${SERVICE_UUID.uuid}")
        } catch (e: Exception) {
            log("广播异常: ${e.message}", LogBuffer.Level.ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════
    //  扫描
    // ═══════════════════════════════════════

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids ?: return
            if (uuids.none { it == SERVICE_UUID }) return

            val address = result.device?.address ?: return
            val name = result.scanRecord?.deviceName ?: ""
            val rssi = result.rssi

            val now = System.currentTimeMillis()

            // 1. 按 MAC 查找
            val existingByMac = deviceMap[address]
            if (existingByMac != null) {
                if (existingByMac.rssiHistory.size > 20) existingByMac.rssiHistory.removeAt(0)
                existingByMac.rssiHistory.add(rssi)
                existingByMac.lastSeen = now
                deviceMap[address] = existingByMac.copy(scanResult = result)
                publishDevices()
                return
            }

            // 2. 按名称查找（防止轮换 MAC 导致同一设备出现多份）
            if (name.isNotBlank()) {
                val existingByName = deviceMap.values.find {
                    it.scanResult.scanRecord?.deviceName == name
                }
                if (existingByName != null) {
                    // 同一设备新 MAC：保留信号更强的，更新时间
                    if (rssi > existingByName.scanResult.rssi) {
                        val oldAddr = existingByName.scanResult.device?.address
                        if (oldAddr != null) deviceMap.remove(oldAddr)
                        val entry = DeviceEntry(result, now)
                        entry.rssiHistory.addAll(existingByName.rssiHistory)
                        entry.rssiHistory.add(rssi)
                        deviceMap[address] = entry
                        log("同名设备 MAC 更换: $name ($address, RSSI提升=${rssi})", LogBuffer.Level.DEBUG)
                    } else {
                        existingByName.lastSeen = now
                        log("同名设备忽略弱信号: $name ($address RSSI=$rssi < 现有${existingByName.scanResult.rssi})", LogBuffer.Level.DEBUG)
                    }
                    publishDevices()
                    retryIndex = 0
                    return
                }
            }

            // 3. 全新设备
            log("发现新设备: $name ($address) RSSI=$rssi")
            val entry = DeviceEntry(result, now)
            entry.rssiHistory.add(rssi)
            deviceMap[address] = entry
            retryIndex = 0
            publishDevices()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            val msg = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "已启动"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "注册失败"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "不支持"
                else -> "未知($errorCode)"
            }
            log("✗ 扫描失败: $msg", LogBuffer.Level.ERROR)

            // 扫描失败后安排重试
            if (isRunning && errorCode != ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                handler.postDelayed({ startScanning() }, 2000L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            log("蓝牙不可用", LogBuffer.Level.ERROR); return
        }
        startScanInternal()
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal() {
        var sc = scanner
        if (sc == null) {
            log("Scanner为null，尝试刷新", LogBuffer.Level.WARN)
            refreshBleInstances()
            sc = scanner
        }
        if (sc == null) {
            log("Scanner仍然为null，放弃扫描", LogBuffer.Level.ERROR)
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            sc.startScan(emptyList(), settings, scanCallback)
            log("启动扫描 (无Filter，手动过滤UUID)")
        } catch (e: SecurityException) {
            log("扫描权限不足", LogBuffer.Level.ERROR)
        } catch (e: Exception) {
            log("扫描异常: ${e.message}", LogBuffer.Level.ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanningInternal() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════
    //  数据发布
    // ═══════════════════════════════════════

    private val deviceColors = listOf(
        androidx.compose.ui.graphics.Color(0xFF00E5FF),
        androidx.compose.ui.graphics.Color(0xFF7C6AFF),
        androidx.compose.ui.graphics.Color(0xFFFF9F43),
        androidx.compose.ui.graphics.Color(0xFFFF3D7F),
        androidx.compose.ui.graphics.Color(0xFF00E676),
    )

    private fun publishDevices() {
        val results = deviceMap.values.toList().mapIndexed { index, entry ->
            val result = entry.scanResult
            val rssi = result.rssi
            val distance = estimateDistance(rssi)
            val address = result.device?.address ?: "unknown_$index"
            val shortAddr = address.takeLast(5).replace(":", "")
            val remoteName = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: "设备$shortAddr"
            // 角度仅用于散点图布局，简单均匀分布，由 RadarView 的 layoutAngle 覆盖
            val angle = (index * 72f + 360f) % 360f

            NearbyDevice(
                id = address, name = remoteName,
                avatar = remoteName.first().toString(),
                color = deviceColors[index % deviceColors.size],
                distance = distance, angle = angle, rssi = rssi
            )
        }
        _discoveredDevices.value = results
    }

    /** RSSI → 距离 (对数路径损耗模型) */
    private fun estimateDistance(rssi: Int): Float {
        val ratio = (TX_POWER_AT_1M - rssi).toFloat() / (10f * PATH_LOSS_EXPONENT)
        val distance = 10.0.pow(ratio.toDouble()).toFloat()
        return distance.coerceIn(0.5f, 50f)
    }

    fun release() {
        stopAll()
        deviceMap.clear()
        handler.removeCallbacksAndMessages(null)
    }
}
