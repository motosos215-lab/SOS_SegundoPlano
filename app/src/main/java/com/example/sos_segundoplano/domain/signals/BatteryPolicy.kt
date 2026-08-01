package com.example.sos_segundoplano.domain.signals

object BatteryPolicy {
    fun percentage(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        val percentage = (level * 100f / scale).toInt()
        return percentage.takeIf { it in 0..100 }
    }
}
