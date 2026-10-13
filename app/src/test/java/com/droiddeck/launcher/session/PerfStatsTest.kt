package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind the HUD's cpu line. No Android: a percentage is a difference between two
 * readings of /proc/stat, and that part can be checked without a device.
 */
class PerfStatsTest {

    @Test fun halfTheTimeIdleIsHalfBusy() {
        assertEquals(50, PerfStats.busyPercent(1000, 500, 1100, 550))
    }

    @Test fun nothingIdleIsAllBusy() {
        assertEquals(100, PerfStats.busyPercent(1000, 500, 1100, 500))
    }

    @Test fun everythingIdleIsNothingBusy() {
        assertEquals(0, PerfStats.busyPercent(1000, 500, 1100, 600))
    }

    /** The first reading has nothing to differ from; a second within the same tick reads as idle. */
    @Test fun noElapsedTimeIsNotAHundredPercent() {
        assertEquals(0, PerfStats.busyPercent(1000, 500, 1000, 500))
    }

    /** Eight cores spending a tenth of their time working is 10%, not 80%. */
    @Test fun itIsAShareOfAllCoresNotASumOfThem() {
        assertEquals(10, PerfStats.busyPercent(0, 0, 800, 720))
    }
}
