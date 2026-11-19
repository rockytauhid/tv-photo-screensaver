// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowSpeedTest {
    @Test
    fun mapsIntervalsToTheNearestPreset() {
        assertEquals(SlideshowSpeed.FAST, SlideshowSpeed.fromIntervalSeconds(3))
        assertEquals(SlideshowSpeed.FAST, SlideshowSpeed.fromIntervalSeconds(5))
        assertEquals(SlideshowSpeed.MEDIUM, SlideshowSpeed.fromIntervalSeconds(6))
        assertEquals(SlideshowSpeed.MEDIUM, SlideshowSpeed.fromIntervalSeconds(11))
        assertEquals(SlideshowSpeed.SLOW, SlideshowSpeed.fromIntervalSeconds(12))
        assertEquals(SlideshowSpeed.SLOW, SlideshowSpeed.fromIntervalSeconds(300))
    }

    @Test
    fun slowIsTheLongestIntervalAndFastTheShortest() {
        assertEquals(15, SlideshowSpeed.SLOW.intervalSeconds)
        assertEquals(8, SlideshowSpeed.MEDIUM.intervalSeconds)
        assertEquals(3, SlideshowSpeed.FAST.intervalSeconds)
    }

    @Test
    fun cyclesFromSlowToMediumToFastAndBack() {
        assertEquals(SlideshowSpeed.MEDIUM, SlideshowSpeed.SLOW.next())
        assertEquals(SlideshowSpeed.FAST, SlideshowSpeed.MEDIUM.next())
        assertEquals(SlideshowSpeed.SLOW, SlideshowSpeed.FAST.next())
    }
}
