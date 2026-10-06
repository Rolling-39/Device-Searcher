package com.proximity.finder.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.proximity.finder.model.NearbyDevice
import java.util.Locale
import kotlin.math.*

/**
 * 雷达散点图：中心为"你"，周围设备按照距离分散在同心圆上
 * - 角度均匀分布（360°/N），防止重叠
 * - 距离映射到半径：越近越靠近中心
 * - 近距离 (<10m) 设备有呼吸动画 + 脉冲环
 */
@Composable
fun RadarView(
    devices: List<NearbyDevice>,
    selectedId: String?,
    onDeviceTap: (NearbyDevice) -> Unit,
    modifier: Modifier = Modifier,
    searchMode: String = "ble"
) {
    // ── 动画 ──
    val infinite = rememberInfiniteTransition(label = "radar")

    val breathScale by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "breath"
    )

    val pulseProgress by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse"
    )

    val centerPulse by infinite.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "center"
    )

    // 雷达扫描线旋转（装饰用）
    val sweepAngle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val bgColor = Color(0xFF0A0E17)
    val borderColor = Color(0xFF1E2A42)
    val cyanColor = Color(0xFF00E5FF)
    val textDim = Color(0xFF3A4A6A)
    val gridColor = Color(0xFF151D2E)

    Canvas(
        modifier = modifier.pointerInput(devices) {
            detectTapGestures { tapPos ->
                val canvasCx = size.width / 2f
                val canvasCy = size.height / 2f
                val maxR = min(canvasCx, canvasCy) * 0.75f
                val dpScale = density.density

                for (i in devices.indices) {
                    val device = devices[i]
                    val angleRad = layoutAngle(i, devices.size)
                    val distRatio = (device.distance / 50f).coerceIn(0.05f, 0.95f)
                    val radius = maxR * 0.15f + maxR * 0.85f * distRatio
                    val cx = canvasCx + radius * cos(angleRad).toFloat()
                    val cy = canvasCy + radius * sin(angleRad).toFloat()
                    val circleR = deviceCircleRadius(device) * dpScale * 1.3f

                    val dx = tapPos.x - cx
                    val dy = tapPos.y - cy
                    if (sqrt(dx * dx + dy * dy) <= circleR + 8f * dpScale) {
                        onDeviceTap(device)
                        break
                    }
                }
            }
        }
    ) {
        val canvasCx = size.width / 2f
        val canvasCy = size.height / 2f
        val maxR = min(canvasCx, canvasCy) * 0.75f
        val dpScale = density.density

        // ── 背景：同心距离环 ──
        val ringDistances = listOf(10f, 20f, 30f, 40f, 50f)
        for (dist in ringDistances) {
            val r = maxR * 0.15f + maxR * 0.85f * (dist / 50f)
            if (r < maxR) {
                drawCircle(
                    color = borderColor.copy(alpha = 0.12f),
                    radius = r, center = Offset(canvasCx, canvasCy),
                    style = Stroke(width = 0.5f * dpScale,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4f * dpScale, 6f * dpScale), 0f
                        ))
                )
                // 距离标签（LAN 模式不画，因为距离不准确）
                if (searchMode != "lan") {
                    val label = "${dist.toInt()}m"
                    val labelText = textMeasurer.measure(label,
                        TextStyle(color = textDim.copy(alpha = 0.4f), fontSize = 8.sp))
                    drawText(labelText, topLeft = Offset(
                        canvasCx + r * 0.38f - labelText.size.width / 2f,
                        canvasCy - labelText.size.height / 2f
                    ))
                }
            }
        }

        // ── 十字参考线 ──
        drawLine(borderColor.copy(alpha = 0.08f),
            Offset(canvasCx, canvasCy - maxR), Offset(canvasCx, canvasCy + maxR),
            strokeWidth = 0.5f * dpScale)
        drawLine(borderColor.copy(alpha = 0.08f),
            Offset(canvasCx - maxR, canvasCy), Offset(canvasCx + maxR, canvasCy),
            strokeWidth = 0.5f * dpScale)

        // ── 雷达扫描线（装饰）──
        val sweepRad = Math.toRadians(sweepAngle.toDouble())
        drawArc(
            color = cyanColor.copy(alpha = 0.04f),
            startAngle = sweepAngle - 15f, sweepAngle = 30f,
            useCenter = true,
            topLeft = Offset(canvasCx - maxR, canvasCy - maxR),
            size = androidx.compose.ui.geometry.Size(maxR * 2, maxR * 2)
        )

        // ── 中心点："你" ──
        val centerRadius = 10f * dpScale
        drawCircle(gridColor, radius = centerRadius + 6f * dpScale, center = Offset(canvasCx, canvasCy))
        drawCircle(cyanColor.copy(alpha = 0.25f), radius = centerRadius + 2f * dpScale,
            center = Offset(canvasCx, canvasCy))
        drawCircle(cyanColor.copy(alpha = centerPulse), radius = 4f * dpScale,
            center = Offset(canvasCx, canvasCy))
        drawCircle(cyanColor, radius = centerRadius,
            center = Offset(canvasCx, canvasCy), style = Stroke(width = 2f * dpScale))

        val youText = textMeasurer.measure("你",
            TextStyle(color = cyanColor, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        drawText(youText, topLeft = Offset(
            canvasCx - youText.size.width / 2f,
            canvasCy + centerRadius + 4f * dpScale
        ))

        // ── 设备节点 ──
        for (i in devices.indices) {
            val device = devices[i]
            val angleRad = layoutAngle(i, devices.size)
            val isNear = device.isNear
            val isSelected = device.id == selectedId

            val distRatio = (device.distance / 50f).coerceIn(0.05f, 0.95f)
            val radius = maxR * 0.15f + maxR * 0.85f * distRatio
            val cx = canvasCx + radius * cos(angleRad).toFloat()
            val cy = canvasCy + radius * sin(angleRad).toFloat()

            val baseCircleR = deviceCircleRadius(device) * dpScale
            val circleR = if (isNear) baseCircleR * breathScale else baseCircleR
            val intensity = device.intensity

            // ── 虚线连接（中心到节点）──
            drawLine(
                device.color.copy(alpha = 0.08f),
                Offset(canvasCx, canvasCy), Offset(cx, cy),
                strokeWidth = 0.5f * dpScale,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(3f * dpScale, 8f * dpScale), 0f
                )
            )

            // ── 近距离脉冲环 ──
            if (isNear) {
                val pulseR = baseCircleR + pulseProgress * 20f * dpScale
                val pulseAlpha = (1f - pulseProgress) * 0.4f
                drawCircle(device.color.copy(alpha = pulseAlpha), radius = pulseR,
                    center = Offset(cx, cy), style = Stroke(width = (1f - pulseProgress * 0.7f) * 2f * dpScale))
            }

            // ── 外发光 ──
            if (isNear || isSelected) {
                drawCircle(device.color.copy(alpha = if (isNear) 0.2f else 0.08f),
                    radius = circleR + 6f * dpScale, center = Offset(cx, cy))
            }

            // ── 设备圆（填充）──
            val fillAlpha = device.fillAlpha
            drawCircle(device.color.copy(alpha = fillAlpha), radius = circleR,
                center = Offset(cx, cy))
            drawCircle(device.color.copy(alpha = fillAlpha * 0.3f),
                radius = circleR * 0.55f,
                center = Offset(cx - circleR * 0.1f, cy - circleR * 0.1f))

            // 描边
            val strokeW = if (isSelected) 2.5f * dpScale else 1.5f * dpScale
            drawCircle(device.color.copy(alpha = if (isSelected) 1f else device.strokeAlpha),
                radius = circleR, center = Offset(cx, cy),
                style = Stroke(width = strokeW))

            // ── 头像字母 ──
            val avatarText = textMeasurer.measure(device.avatar,
                TextStyle(color = Color.White.copy(alpha = if (isNear) 1f else 0.7f),
                    fontSize = (14 + intensity * 6).sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center))
            drawText(avatarText, topLeft = Offset(
                cx - avatarText.size.width / 2f, cy - avatarText.size.height / 2f))

            // ── 名字 ──
            val nameText = textMeasurer.measure(device.name,
                TextStyle(color = device.color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center))
            drawText(nameText, topLeft = Offset(
                cx - nameText.size.width / 2f, cy + circleR + 4f * dpScale))

            // ── 距离（LAN 模式用信号等级代替数值）──
            if (searchMode != "lan") {
                val distStr = "${String.format(Locale.US, "%.1f", device.distance)}m"
                val distText = textMeasurer.measure(distStr,
                    TextStyle(color = Color.White.copy(alpha = if (isNear) 1f else 0.7f),
                        fontSize = 14.sp, fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center))
                drawText(distText, topLeft = Offset(
                    cx - distText.size.width / 2f, cy + circleR + 4f * dpScale + nameText.size.height + 1f * dpScale))
            }
        }
    }
}

/** 等分角度布局 */
private fun layoutAngle(index: Int, total: Int): Double {
    if (total <= 0) return 0.0
    return (index * (360.0 / total) - 90.0) * Math.PI / 180.0
}

/** 设备圆半径：距离越近越大，在 22dp ~ 38dp 之间 */
private fun deviceCircleRadius(device: NearbyDevice): Float {
    return 22f + device.intensity * 16f
}

private val EaseInOutSine: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
