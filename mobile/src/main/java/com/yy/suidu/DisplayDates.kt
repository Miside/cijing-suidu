package com.yy.suidu

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DisplayDates {
    fun article(time: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "M月d日" else "yyyy年M月d日"))
        }
    }
}
