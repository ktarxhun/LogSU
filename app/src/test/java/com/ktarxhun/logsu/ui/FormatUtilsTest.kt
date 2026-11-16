package com.ktarxhun.logsu.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun formatsSecondsUnderOneHourAsMinutesAndSeconds() {
        assertEquals("00:00", FormatUtils.formatDurationSeconds(-5L))
        assertEquals("00:45", FormatUtils.formatDurationSeconds(45L))
        assertEquals("59:59", FormatUtils.formatDurationSeconds(3599L))
    }

    @Test
    fun formatsSecondsOneHourOrMoreWithHours() {
        assertEquals("01:00:00", FormatUtils.formatDurationSeconds(3600L))
        assertEquals("02:05:09", FormatUtils.formatDurationSeconds(7509L))
    }

    @Test
    fun formatsMillisConsistently() {
        assertEquals("00:00", FormatUtils.formatDurationMillis(-100L))
        assertEquals("01:30", FormatUtils.formatDurationMillis(90_500L))
        assertEquals("01:01:05", FormatUtils.formatDurationMillis(3_665_999L))
    }
}
