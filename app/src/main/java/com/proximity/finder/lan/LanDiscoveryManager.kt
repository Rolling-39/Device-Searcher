package com.proximity.finder.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.proximity.finder.model.NearbyDevice
import com.proximity.finder.util.LogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.pow

class LanDiscoveryManager(private val context: Context) {

    companion object {
        private const val DISCOVERY_PORT = 19876
        private const val BROADCAST_INTERVAL = 3_000L
        private const val DEVICE_TIMEOUT = 20_000L
        private const val CLEANUP_INTERVAL = 8_000L
        private const val APP_IDENTIFIER = "DeviceSearcher"
        private const val PROTO_VERSION = "4.0"  // 升级协议版本，携带 RSSI

        // RSSI → 距离 转换参数（log-distance path loss model）
        private const val TX_POWER_1M = -40f   // 1米处参考 RSSI（WiFi 2.4GHz 典型值）
        private const val PATH_LOSS_N = 2.5f    // 室内路径损耗指数
        private const val MIN_DISTANCE = 1f
        private const val MAX_DISTANCE = 50f
    }

    private val lanColors = listOf(
        androidx.compose.ui.graphics.Color(0xFF42A5F5),
        androidx.compose.ui.graphics.Color(0xFFAB47BC),
        androidx.compose.ui.graphics.Color(0xFFFF7043),
        androidx.compose.ui.graphics.Color(0xFF66BB6A),
        androidx.compose.ui.graphics.Color(0xFFEF5350),
    )

    private val wifiManager: WifiManager
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val handler = Handler(Looper.getMainLooper())
    private var broadcastExecutor = Executors.newSingleThreadExecutor()
    private var listenSocket: DatagramSocket? = null
    private var listenThread: Thread? = null
    private var isRunning = false

    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()

    private data class LanEntry(
        val ip: String,
        var nickname: String,
        var rssi: Int,
        var lastSeen: Long,
    )
    private val deviceMap = ConcurrentHashMap<String, LanEntry>()

    var nickname: String = ""

    fun hasActiveNetwork(): Boolean {
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (ni.isLoopback || !ni.isUp) return@forEach
                ni.inetAddresses.toList().forEach { addr ->
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /** 获取当前 WiFi RSSI（dBm），不可用时返回 Int.MIN_VALUE */
    private fun getWifiRssi(): Int {
        return try {
            val info = wifiManager.connectionInfo
            if (info != null && info.rssi != 0) info.rssi else Int.MIN_VALUE
        } catch (_: Exception) {
            Int.MIN_VALUE
        }
    }

    /** RSSI → 距离换算（log-distance path loss model） */
    private fun rssiToDistance(rssi: Int): Float {
        if (rssi == Int.MIN_VALUE || rssi >= 0) return 50f  // 无有效 RSSI
        val dist = 10.0.pow((TX_POWER_1M - rssi) / (10.0 * PATH_LOSS_N)).toFloat()
        return dist.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /** RSSI → 信号强度等级 */
    private fun rssiToTier(rssi: Int): String = when {
        rssi == Int.MIN_VALUE || rssi >= 0 -> "未知"
        rssi > -50 -> "强"
        rssi > -70 -> "中"
        else -> "弱"
    }

    private fun log(msg: String, level: LogBuffer.Level = LogBuffer.Level.INFO) {
        LogBuffer.add("LAN", msg, level)
    }

    // ═══════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════

    fun start() {
        if (isRunning) return
        isRunning = true
        log("========== UDP 局域网发现启动 v5.3 ==========")

        val rssi = getWifiRssi()
        if (rssi != Int.MIN_VALUE) {
            log("当前 WiFi RSSI: $rssi dBm (信号: ${rssiToTier(rssi)})")
        } else {
            log("无法获取 WiFi RSSI（可能是热点模式）", LogBuffer.Level.WARN)
        }

        try {
            listenSocket = DatagramSocket(null)
            listenSocket?.reuseAddress = true
            listenSocket?.bind(InetSocketAddress(DISCOVERY_PORT))
            listenSocket?.soTimeout = 1500
            log("UDP 监听端口 $DISCOVERY_PORT")
        } catch (e: Exception) {
            log("UDP Socket 创建失败: ${e.message}", LogBuffer.Level.ERROR)
            isRunning = false
            return
        }

        listenThread = Thread({ listenLoop() }, "LanListen")
        listenThread?.isDaemon = true
        listenThread?.start()

        broadcastExecutor.execute { sendBroadcast() }
        handler.postDelayed(broadcastRunnable, BROADCAST_INTERVAL)
        handler.postDelayed(cleanupRunnable, CLEANUP_INTERVAL)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        broadcastExecutor.shutdownNow()
        broadcastExecutor = Executors.newSingleThreadExecutor()
        listenThread?.interrupt()
        try { listenSocket?.close() } catch (_: Exception) {}
        listenSocket = null
        deviceMap.clear()
        _devices.value = emptyList()
        log("UDP 局域网发现已停止")
    }

    // ── 监听循环 ──
    private fun listenLoop() {
        val buf = ByteArray(512)

        while (isRunning && !Thread.currentThread().isInterrupted) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                val sock = listenSocket ?: break
                sock.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!isRunning) break
                continue
            }

            try {
                val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val senderIp = packet.address.hostAddress ?: continue

                if (senderIp in getLocalIps()) continue

                // 协议格式: "DeviceSearcher|nickname|4.0|rssi"
                val parts = msg.split("|", limit = 4)
                if (parts.size < 2 || parts[0] != APP_IDENTIFIER) continue

                val remoteName = parts[1].takeIf { it.isNotBlank() } ?: "LAN_${senderIp.takeLast(4)}"
                val remoteRssi = if (parts.size >= 4) parts[3].toIntOrNull() ?: Int.MIN_VALUE else Int.MIN_VALUE
                val now = System.currentTimeMillis()

                val entry = deviceMap[senderIp]
                if (entry != null) {
                    entry.nickname = remoteName
                    if (remoteRssi != Int.MIN_VALUE) entry.rssi = remoteRssi
                    entry.lastSeen = now
                } else {
                    log("UDP 发现设备: $remoteName ($senderIp) RSSI=$remoteRssi")
                    deviceMap[senderIp] = LanEntry(senderIp, remoteName, remoteRssi, now)
                }
                publishDevices()

                // 收到报文就单播回复，维持双向心跳
                // （热点 AP 广播不对称，靠单播让对方持续发现我）
                broadcastExecutor.execute { sendUnicast(senderIp) }
            } catch (_: Exception) {}
        }
    }

    // ── 构建报文字符串 ──
    private fun buildPayload(): String {
        val rssi = getWifiRssi()
        return if (rssi != Int.MIN_VALUE) {
            "$APP_IDENTIFIER|$nickname|$PROTO_VERSION|$rssi"
        } else {
            "$APP_IDENTIFIER|$nickname|$PROTO_VERSION"
        }
    }

    // ── 发送广播 ──
    private fun sendBroadcast() {
        val targets = getBroadcastTargets()
        if (targets.isEmpty()) return

        var sendSocket: DatagramSocket? = null
        try {
            sendSocket = DatagramSocket()
            sendSocket.broadcast = true
            sendSocket.reuseAddress = true

            val payload = buildPayload()
            val data = payload.toByteArray(Charsets.UTF_8)
            var successCount = 0
            for (target in targets) {
                try {
                    sendSocket.send(DatagramPacket(data, data.size, target, DISCOVERY_PORT))
                    successCount++
                } catch (_: Exception) {}
            }
            if (successCount > 0) {
                log("广播 → ${targets.joinToString { it.hostAddress ?: "?" }}", LogBuffer.Level.DEBUG)
            }
        } catch (e: Exception) {
            log("广播失败: ${e.message}", LogBuffer.Level.ERROR)
        } finally {
            try { sendSocket?.close() } catch (_: Exception) {}
        }
    }

    // ── 单播回复（复用监听 socket，确保走同一网络接口）──
    private fun sendUnicast(targetIp: String) {
        try {
            val sock = listenSocket ?: return
            val payload = buildPayload()
            val data = payload.toByteArray(Charsets.UTF_8)
            val addr = InetAddress.getByName(targetIp)
            sock.send(DatagramPacket(data, data.size, addr, DISCOVERY_PORT))
        } catch (e: Exception) {
            // 单播失败不频繁打日志，3秒一次太吵
        }
    }

    // ── 获取广播目标（只保留私有子网广播地址）──
    private fun getBroadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (ni.isLoopback || !ni.isUp) return@forEach
                ni.interfaceAddresses?.forEach { ifaceAddr ->
                    val inet = ifaceAddr.address
                    if (inet is Inet4Address && !inet.isLoopbackAddress && !inet.isLinkLocalAddress) {
                        val octets = inet.address
                        val isPrivate = (
                            octets[0].toInt() == 10 ||
                            (octets[0].toInt() == 172 && octets[1].toInt() in 16..31) ||
                            (octets[0].toInt() == 192 && octets[1].toInt() == 168)
                        )
                        if (!isPrivate) return@forEach

                        val prefixLen = ifaceAddr.networkPrefixLength.toInt()
                        if (prefixLen in 1..31) {
                            calculateBroadcast(inet, prefixLen)?.let { targets.add(it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (targets.isEmpty()) {
            try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        }
        return targets.distinct()
    }

    private fun calculateBroadcast(ip: Inet4Address, prefixLen: Int): InetAddress? {
        try {
            if (prefixLen <= 0 || prefixLen >= 32) return null
            val ipBytes = ip.address
            val mask = (-1 shl (32 - prefixLen)).inv()
            val broadcastBytes = ByteArray(4) { i ->
                (ipBytes[i].toInt() and 0xFF or (mask shr (24 - i * 8) and 0xFF)).toByte()
            }
            return InetAddress.getByAddress(broadcastBytes)
        } catch (_: Exception) {
            return null
        }
    }

    private val broadcastRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            broadcastExecutor.execute { sendBroadcast() }
            handler.postDelayed(this, BROADCAST_INTERVAL)
        }
    }

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val now = System.currentTimeMillis()
            val removed = mutableListOf<String>()
            deviceMap.entries.forEach { (ip, entry) ->
                if (now - entry.lastSeen > DEVICE_TIMEOUT) removed.add(ip)
            }
            if (removed.isNotEmpty()) {
                removed.forEach { deviceMap.remove(it) }
                publishDevices()
            }
            handler.postDelayed(this, CLEANUP_INTERVAL)
        }
    }

    private fun getLocalIps(): Set<String> {
        val ips = mutableSetOf<String>()
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (ni.isLoopback || !ni.isUp) return@forEach
                ni.inetAddresses.toList().forEach { addr ->
                    if (!addr.isLoopbackAddress && addr.hostAddress != null) {
                        ips.add(addr.hostAddress!!)
                    }
                }
            }
        } catch (_: Exception) {}
        return ips
    }

    private fun publishDevices() {
        val results = deviceMap.values.toList().mapIndexed { index, entry ->
            val distance = rssiToDistance(entry.rssi)
            NearbyDevice(
                id = "lan_${entry.ip}",
                name = entry.nickname,
                avatar = entry.nickname.first().toString(),
                color = lanColors[index % lanColors.size],
                distance = distance,
                angle = (index * 90f + 45f) % 360f,
                rssi = entry.rssi,
                source = "lan"
            )
        }
        _devices.value = results
    }
}
