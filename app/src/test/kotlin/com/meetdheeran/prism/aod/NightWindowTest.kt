package com.meetdheeran.prism.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class NightWindowTest {
    @Test fun `window crossing midnight`() {
        val start = 23 * 60; val end = 7 * 60
        assertTrue(AodService.inNight(start, end, LocalTime.of(23, 30)))
        assertTrue(AodService.inNight(start, end, LocalTime.of(3, 0)))
        assertFalse(AodService.inNight(start, end, LocalTime.of(7, 0)))
        assertFalse(AodService.inNight(start, end, LocalTime.of(12, 0)))
    }

    @Test fun `window within one day and empty window`() {
        assertTrue(AodService.inNight(0, 7 * 60, LocalTime.of(0, 0)))
        assertFalse(AodService.inNight(0, 7 * 60, LocalTime.of(7, 1)))
        assertFalse(AodService.inNight(5 * 60, 5 * 60, LocalTime.of(5, 0)))
    }
}
