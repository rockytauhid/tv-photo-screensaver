package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowSpeedTest {
    @Test
    fun mapsIntervalsToTheNearestPreset() {
        assertEquals(SlideshowSpeed.SLOW, SlideshowSpeed.fromIntervalSeconds(3))
        assertEquals(SlideshowSpeed.SLOW, SlideshowSpeed.fromIntervalSeconds(5))
        assertEquals(SlideshowSpeed.MEDIUM, SlideshowSpeed.fromIntervalSeconds(6))
        assertEquals(SlideshowSpeed.MEDIUM, SlideshowSpeed.fromIntervalSeconds(11))
        assertEquals(SlideshowSpeed.FAST, SlideshowSpeed.fromIntervalSeconds(12))
        assertEquals(SlideshowSpeed.FAST, SlideshowSpeed.fromIntervalSeconds(300))
    }

    @Test
    fun cyclesFromSlowToMediumToFastAndBack() {
        assertEquals(SlideshowSpeed.MEDIUM, SlideshowSpeed.SLOW.next())
        assertEquals(SlideshowSpeed.FAST, SlideshowSpeed.MEDIUM.next())
        assertEquals(SlideshowSpeed.SLOW, SlideshowSpeed.FAST.next())
    }
}
