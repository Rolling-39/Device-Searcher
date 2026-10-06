package com.proximity.finder.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import android.os.Looper
import com.proximity.finder.model.NearbyDevice
import com.proximity.finder.util.LogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class WifiProximityManager(private val context: Context) {

    companion object {
        const val SERVICE_TYPE = "_device-searcher._tcp."
        const val SERVICE_INSTANCE = "DeviceSearcher"
        private const val DEVICE_TIMEOUT_MS = 30_000L
        private const val CLEANUP_INTERVAL_MS = 8_000L
        private const val DISCOVERY_RETRY_DELAY_MS = 10_000L
    }

    private val wifiColors = listOf(
        androidx.compose.ui.graphics.Color(0xFF42A5F5),
        androidx.compose.ui.graphics.Color(0xFFAB47BC),
        androidx.compose.ui.graphics.Color(0xFFFF7043),
        androidx.compose.ui.graphics.Color(0xFF66BB6A),
        androidx.compose.ui.graphics.Color(0xFFEF5350),
    )

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private val handler = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _wifiDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val wifiDevices: StateFlow<List<NearbyDevice>> = _wifiDevices.asStateFlow()

    private data class WifiEntry(
        val device: WifiP2pDevice,
        var lastSeen: Long,
        var nickname: String = "",
    )
    private val deviceMap = ConcurrentHashMap<String, WifiEntry>()
    private var isRunning = false
    private var discoveryRetryCount = 0
    private var discoveryInProgress = false  // 防止并发发现流程

    var nickname: String = ""

    private fun log(msg: String, level: LogBuffer.Level = LogBuffer.Level.INFO) {
        LogBuffer.add("WiFi", msg, level)
    }

    // ── WiFi 状态广播 ──
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        log("WiFi Direct 就绪")
                        // 框架就绪后延迟再启动发现，避免 BUSY
                        handler.postDelayed({ doDiscoverySequence() }, 500L)
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (isRunning) requestPeers()
                }
            }
        }
    }

    // ═══════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════

    fun start() {
        if (isRunning) return
        if (wifiP2pManager == null) {
            log("WiFi Direct 不可用", LogBuffer.Level.WARN); return
        }
        isRunning = true
        discoveryRetryCount = 0
        log("========== WiFi Direct 启动 ==========")

        // 获取组播锁：国产 ROM 激进省电会拦截组播包导致 WiFi Direct 静默失败
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null) {
            multicastLock = wm.createMulticastLock("DeviceSearcher:WiFi")
            multicastLock?.acquire()
            log("组播锁已获取")
        }

        // 初始化 Channel（带监听器感知就绪状态）
        channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) {
            log("WiFi P2P Channel 断开")
        }
        setupDnsSdListeners()

        // 注册广播
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        context.registerReceiver(wifiStateReceiver, filter)

        // 启动发现流程（延迟以确保框架就绪）
        handler.postDelayed({ doDiscoverySequence() }, 1000L)

        // 定时清理
        handler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL_MS)
    }

    fun stop() {
        isRunning = false
        discoveryInProgress = false
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        try { context.unregisterReceiver(wifiStateReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        try { wifiP2pManager?.clearLocalServices(channel, null) } catch (_: Exception) {}
        try { wifiP2pManager?.stopPeerDiscovery(channel, null) } catch (_: Exception) {}
        try { wifiP2pManager?.clearServiceRequests(channel, null) } catch (_: Exception) {}
        deviceMap.clear()
        _wifiDevices.value = emptyList()
        discoveryRetryCount = 0
        log("WiFi Direct 已停止")
    }

    // ── 发现流程（串行，每步之间有延迟避免 BUSY）──
    private var registerRetryCount = 0

    private fun doDiscoverySequence() {
        if (!isRunning || discoveryInProgress) {
            if (discoveryInProgress) log("发现流程进行中，跳过重复触发", LogBuffer.Level.DEBUG)
            return
        }
        discoveryInProgress = true
        registerRetryCount = 0
        log("开始 WiFi 发现流程", LogBuffer.Level.DEBUG)

        // 1. 清除旧服务（延迟确保框架完成清理）
        wifiP2pManager?.clearLocalServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                handler.postDelayed({ registerServiceAndDiscover() }, 800L)
            }
            override fun onFailure(code: Int) {
                // 忽略错误，继续
                handler.postDelayed({ registerServiceAndDiscover() }, 1000L)
            }
        })
    }

    private fun registerServiceAndDiscover() {
        if (!isRunning) { discoveryInProgress = false; return }

        // 2. 注册本地服务
        val txtRecord = HashMap<String, String>().apply {
            put("app", "DeviceSearcher")
            put("name", nickname)
            put("ver", "2.0")
        }
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE, SERVICE_TYPE, txtRecord
        )
        wifiP2pManager?.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("WiFi 本地服务已注册")
                registerRetryCount = 0
                handler.postDelayed({ startPeerAndServiceDiscovery() }, 500L)
            }
            override fun onFailure(code: Int) {
                val msg = when (code) {
                    0 -> "错误"
                    1 -> "不支持"
                    2 -> "忙碌"
                    else -> "未知($code)"
                }
                log("本地服务注册失败: $msg (code=$code)", LogBuffer.Level.WARN)
                if (code == 2 && registerRetryCount < 3) {
                    // BUSY：原地重试，等框架空闲
                    registerRetryCount++
                    handler.postDelayed({ registerServiceAndDiscover() }, 1500L)
                } else {
                    // 其他错误或重试耗尽：尝试继续（可能之前已注册成功）
                    handler.postDelayed({ startPeerAndServiceDiscovery() }, 500L)
                }
            }
        })
    }

    private fun startPeerAndServiceDiscovery() {
        if (!isRunning) { discoveryInProgress = false; return }

        // 3. 启动对等发现
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("WiFi 对等发现已启动")
                discoveryRetryCount = 0
                handler.postDelayed({ startServiceDiscovery() }, 500L)
            }
            override fun onFailure(code: Int) {
                val msg = when (code) {
                    WifiP2pManager.BUSY -> "忙碌"
                    WifiP2pManager.ERROR -> "错误"
                    else -> "未知($code)"
                }
                log("WiFi 对等发现失败: $msg (code=$code)", LogBuffer.Level.WARN)
                if (code == WifiP2pManager.BUSY) {
                    // BUSY：稍等重试
                    handler.postDelayed({ startPeerAndServiceDiscovery() }, 2000L)
                } else {
                    retryDiscoveryLater()
                }
            }
        })
    }

    private fun startServiceDiscovery() {
        if (!isRunning) {
            discoveryInProgress = false
            return
        }

        // 4. DNS-SD 服务发现 — 限定只搜索同 App 服务类型
        // 先清除旧请求，避免重复注册
        wifiP2pManager?.clearServiceRequests(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                addServiceRequestAndDiscover()
            }
            override fun onFailure(code: Int) {
                // 忽略清除失败，直接尝试添加
                addServiceRequestAndDiscover()
            }
        })
    }

    private fun addServiceRequestAndDiscover() {
        if (!isRunning) {
            discoveryInProgress = false
            return
        }

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        wifiP2pManager?.addServiceRequest(channel, serviceRequest,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("DNS-SD 服务请求已注册 ($SERVICE_TYPE)", LogBuffer.Level.DEBUG)
                }
                override fun onFailure(code: Int) {
                    log("DNS-SD 服务请求失败: $code", LogBuffer.Level.DEBUG)
                }
            })

        wifiP2pManager?.discoverServices(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("WiFi 服务发现已启动")
            }
            override fun onFailure(code: Int) {
                log("WiFi 服务发现失败: $code", LogBuffer.Level.DEBUG)
            }
        })

        // 本轮发现流程完成，释放锁
        discoveryInProgress = false

        // 安排下次重新发现
        handler.postDelayed({ doDiscoverySequence() }, DISCOVERY_RETRY_DELAY_MS)
    }

    private fun retryDiscoveryLater() {
        discoveryInProgress = false
        discoveryRetryCount++
        val delay = (DISCOVERY_RETRY_DELAY_MS * discoveryRetryCount).coerceAtMost(60_000L)
        log("WiFi 发现将在 ${delay / 1000}s 后重试 (第${discoveryRetryCount}次)")
        handler.postDelayed({ doDiscoverySequence() }, delay)
    }

    // ── 获取对等设备列表 ──
    // 不直接添加设备，只记录日志；真正添加由 DNS-SD 回调完成（已确认是同 App）
    private fun requestPeers() {
        wifiP2pManager?.requestPeers(channel) { peers: WifiP2pDeviceList? ->
            val count = peers?.deviceList?.size ?: 0
            log("对等设备列表: ${count}台 (等待DNS-SD确认)", LogBuffer.Level.DEBUG)
        }
    }

    // ── DNS-SD 回调 ──
    private fun setupDnsSdListeners() {
        wifiP2pManager?.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, srcDevice ->
                // 服务发现：检查是否为同 App 服务类型
                if (registrationType.contains(SERVICE_TYPE)) {
                    log("DNS-SD 发现同类型服务: $instanceName (${srcDevice.deviceAddress})", LogBuffer.Level.DEBUG)
                    // 先建立临时条目，等 TXT 记录回调确认 app 字段后再最终确认
                    val existing = deviceMap[srcDevice.deviceAddress]
                    if (existing != null) {
                        existing.lastSeen = System.currentTimeMillis()
                    }
                    // 不在这里添加新条目，等 TXT 记录回调拿到 app 字段再确认
                }
            },
            { fullDomainName, recordMap, srcDevice ->
                // TXT 记录回调：确认是本 App 设备后加入
                val appName = recordMap?.get("app")?.toString() ?: ""
                if (appName != "DeviceSearcher") {
                    return@setDnsSdResponseListeners
                }

                val remoteName = recordMap["name"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: srcDevice.deviceName.ifBlank { "WiFi_${srcDevice.deviceAddress.takeLast(4)}" }

                val existing = deviceMap[srcDevice.deviceAddress]
                if (existing != null) {
                    existing.nickname = remoteName
                    existing.lastSeen = System.currentTimeMillis()
                } else {
                    log("DNS-SD 确认设备: $remoteName (${srcDevice.deviceAddress})")
                    deviceMap[srcDevice.deviceAddress] = WifiEntry(
                        srcDevice, System.currentTimeMillis(), remoteName
                    )
                }
                publishDevices()
            })
    }

    // ── 定时清理 ──
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
                publishDevices()
            }
            handler.postDelayed(this, CLEANUP_INTERVAL_MS)
        }
    }

    // ── 数据发布 ──
    private fun publishDevices() {
        val results = deviceMap.values.toList().mapIndexed { index, entry ->
            val address = entry.device.deviceAddress.ifEmpty { "wifi_$index" }
            val displayName = entry.nickname.ifBlank {
                entry.device.deviceName.ifBlank { "WiFi${address.takeLast(4)}" }
            }

            NearbyDevice(
                id = "wifi_$address",
                name = displayName,
                avatar = displayName.first().toString(),
                color = wifiColors[index % wifiColors.size],
                distance = 55f, // WiFi 设备统一标为远距离
                angle = (index * 90f + 45f) % 360f,
                rssi = -80,
                source = "wifi"
            )
        }
        _wifiDevices.value = results
    }
}
