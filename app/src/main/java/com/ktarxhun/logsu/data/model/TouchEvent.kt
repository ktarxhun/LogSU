package com.ktarxhun.logsu.data.model

data class TouchEvent(
    val timestampMs: Long,
    val x: Float,
    val y: Float,
    val action: Int // 0: DOWN, 1: UP, 2: MOVE
) {
    fun toLogLine(): String {
        return "$timestampMs,$x,$y,$action"
    }

    companion object {
        fun fromLogLine(line: String): TouchEvent? {
            val parts = line.split(",")
            if (parts.size >= 4) {
                return try {
                    TouchEvent(
                        timestampMs = parts[0].toLong(),
                        x = parts[1].toFloat(),
                        y = parts[2].toFloat(),
                        action = parts[3].toInt()
                    )
                } catch (e: Exception) {
                    null
                }
            }
            return null
        }
    }
}
