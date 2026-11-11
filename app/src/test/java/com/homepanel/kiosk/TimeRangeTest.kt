package com.homepanel.kiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeRangeTest {
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test fun rangeInsideOneDay() {
        assertTrue(TimeRange.contains(at(6), at(9), at(6)))
        assertTrue(TimeRange.contains(at(6), at(9), at(8, 59)))
        assertFalse("la fin est exclue", TimeRange.contains(at(6), at(9), at(9)))
        assertFalse(TimeRange.contains(at(6), at(9), at(5, 59)))
    }

    @Test fun rangeAcrossMidnight() {
        // Heures calmes ou skin de nuit : 21:30 → 06:00
        assertTrue(TimeRange.contains(at(21, 30), at(6), at(23)))
        assertTrue(TimeRange.contains(at(21, 30), at(6), at(0)))
        assertTrue(TimeRange.contains(at(21, 30), at(6), at(5, 59)))
        assertFalse(TimeRange.contains(at(21, 30), at(6), at(6)))
        assertFalse(TimeRange.contains(at(21, 30), at(6), at(12)))
        assertFalse(TimeRange.contains(at(21, 30), at(6), at(21, 29)))
    }

    @Test fun sameStartAndEndIsEmpty() {
        for (m in listOf(0, at(7), at(23, 59))) assertFalse(TimeRange.contains(at(7), at(7), m))
    }
}
