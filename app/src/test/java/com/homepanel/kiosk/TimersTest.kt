package com.homepanel.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class TimersTest {
    @Test fun format() {
        assertEquals("0:01", KitchenTimers.format(1))
        assertEquals("1:00", KitchenTimers.format(60_000))
        assertEquals("9:59", KitchenTimers.format(599_000))
        assertEquals("1:00:00", KitchenTimers.format(3_600_000))
        assertEquals("1:05:09", KitchenTimers.format(3_909_000))
    }

    @Test fun aNamedTimerShowsItsName() {
        I18n.apply("fr")
        assertEquals("Pâtes", KitchenTimer(1, "10:00", 600_000, "Pâtes").title)
        assertEquals("Minuterie de 10:00", KitchenTimer(2, "10:00", 600_000).title)
    }
}

class NumberFormatTest {
    @org.junit.Test fun noNegativeZero() {
        org.junit.Assert.assertFalse(Present.num(-0.04).startsWith("-"))
        org.junit.Assert.assertFalse(Present.num(-0.0).startsWith("-"))
        org.junit.Assert.assertTrue(Present.num(-0.6).startsWith("-") || Present.num(-0.6).startsWith("−"))
        org.junit.Assert.assertTrue(Present.num(-3.0).contains("3"))
        org.junit.Assert.assertEquals(Present.num(2.0), Present.num(2.0).trimStart('-'))
    }
}
