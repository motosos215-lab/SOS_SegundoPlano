package com.example.sos_segundoplano.domain.offline

fun interface WallClock {
    fun currentTimeMillis(): Long
}

object SystemWallClock : WallClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
