package com.ktarxhun.logsu.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {
    private val cardDateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("tr"))
    }

    private val shortDateFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    }

    private val eventTimeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    fun formatCardDate(timeMs: Long): String = cardDateFormat.get()!!.format(Date(timeMs))

    fun formatShortDate(timeMs: Long): String = shortDateFormat.get()!!.format(Date(timeMs))

    fun formatEventTime(timeMs: Long): String = eventTimeFormat.get()!!.format(Date(timeMs))

    fun formatDurationSeconds(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        return if (safe >= 3600L) {
            String.format(Locale.US, "%02d:%02d:%02d", safe / 3600L, (safe % 3600L) / 60L, safe % 60L)
        } else {
            String.format(Locale.US, "%02d:%02d", safe / 60L, safe % 60L)
        }
    }

    fun formatDurationMillis(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        return formatDurationSeconds(totalSec)
    }
}
