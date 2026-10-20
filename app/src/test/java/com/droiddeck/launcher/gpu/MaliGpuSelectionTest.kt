package com.droiddeck.launcher.gpu

import org.junit.Assert.*
import org.junit.Test

class MaliGpuSelectionTest {
    private val mali = GpuInfo("Mali-G615 MC6", 615, GpuInfo.Family.MALI, "MT6897", false)

    @Test fun maliNeverSelectsAnAdrenoTurnipPair() {
        assertNull(DriverPairs.recommendedKey(mali, emptyList()))
        val asset = TurnipReleases.Asset("DroidDeck", "DD-Turnip-v0.1.0", "DD-Turnip.zip", "https://example.invalid/driver", 1,
            false, "test", pair = DriverPairs.ddTurnip("DD-Turnip-v0.1.0"), bundle = true)
        val pairs = DriverPairs.from(TurnipReleases.Check(listOf(asset), emptyList(), emptyList(), 0))
        assertFalse(pairs.single().suits(mali))
    }

    @Test fun experimentalIsNeverReportedAsTestedHardware() {
        assertNotEquals(GpuInfo.Support.TESTED, mali.support)
        // Every Android the app installs on: the driver's kbase front end decides whether a Mali
        // generation runs, not the platform release (R20; the session gate had kept 15+ before).
        assertEquals(GpuInfo.Support.UNTESTED, mali.support)
        assertTrue(mali.supportText.contains("untested"))
    }
}
