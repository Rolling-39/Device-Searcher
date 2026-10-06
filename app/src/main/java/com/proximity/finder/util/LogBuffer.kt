package com.proximity.finder.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局日志缓冲区
 * 捕获 BLE 和系统日志，供 App 内日志查看器使用
 */
object LogBuffer {

    data class Entry(
        val time: String,
        val tag: String,
        val message: String,
        val level: Level
    )

    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val buffer = ArrayDeque<Entry>(200)
    private const val MAX_SIZE = 200

    fun add(tag: String, message: String, level: Level = Level.INFO) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(buffer) {
            if (buffer.size >= MAX_SIZE) buffer.removeFirst()
            buffer.addLast(Entry(time, tag, message, level))
            _entries.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }
}
