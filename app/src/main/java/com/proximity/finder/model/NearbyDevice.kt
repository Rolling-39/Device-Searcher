package com.proximity.finder.model

import androidx.compose.ui.graphics.Color

/**
 * 附近设备数据模型
 *
 * @param id           唯一标识
 * @param name         设备/用户昵称
 * @param avatar       头像字母
 * @param color        设备标识色
 * @param distance     估算距离（米）
 * @param angle        相对方向角（度），0=正上方，顺时针
 * @param rssi         原始信号强度
 */
data class NearbyDevice(
    val id: String,
    val name: String,
    val avatar: String,
    val color: Color,
    val distance: Float,
    val angle: Float,
    val rssi: Int = -60,
    val source: String = "ble"  // "ble" | "wifi"
) {
    companion object {
        /** 最大有效距离，用于归一化计算 */
        const val MAX_DISTANCE = 50f

        /** 近距离阈值 */
        const val NEAR_THRESHOLD = 10f
    }

    /** 归一化强度 0~1，1=最近 */
    val intensity: Float
        get() = (1f - (distance.coerceIn(0f, MAX_DISTANCE) / MAX_DISTANCE))

    /** 圆形半径 (32~38dp) */
    val circleRadius: Float
        get() = 32f + intensity * 6f

    /** 偏移量 (4~20dp) */
    val circleOffset: Float
        get() = 4f + intensity * 16f

    /** 填充不透明度 */
    val fillAlpha: Float
        get() = 0.08f + intensity * 0.52f

    /** 描边不透明度 */
    val strokeAlpha: Float
        get() = 0.25f + intensity * 0.75f

    /** 是否在近距离范围内 */
    val isNear: Boolean
        get() = distance <= NEAR_THRESHOLD
}
