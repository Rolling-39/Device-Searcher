package com.proximity.finder.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 震动反馈管理器
 *
 * 距离越近 → 震动越强、次数越多、频率越快
 */
object HapticUtil {

    fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * 根据距离触发不同强度的震动
     * @param distance 最近设备的距离（米）
     */
    fun vibrateNear(context: Context, distance: Float) {
        val vibrator = getVibrator(context)
        val intensity = (1f - distance.coerceIn(0f, 10f) / 10f)
        val amplitude = (intensity * 190 + 65).toInt().coerceIn(65, 255)

        val timings: LongArray
        val amplitudes: IntArray

        when {
            distance < 2f -> {
                // 极近（<2m）：四连急促震动
                timings = longArrayOf(0, 25, 35, 25, 35, 25, 35, 25)
                amplitudes = intArrayOf(0, amplitude, 0, amplitude, 0, amplitude, 0, amplitude)
            }
            distance < 5f -> {
                // 近（2-5m）：三连震
                timings = longArrayOf(0, 35, 55, 35, 55, 35)
                amplitudes = intArrayOf(0, amplitude, 0, amplitude, 0, amplitude)
            }
            distance < 8f -> {
                // 中（5-8m）：双震
                timings = longArrayOf(0, 40, 80, 40)
                amplitudes = intArrayOf(0, amplitude, 0, amplitude)
            }
            else -> {
                // 远（8-10m）：单震
                timings = longArrayOf(0, 60)
                amplitudes = intArrayOf(0, amplitude)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }
}
