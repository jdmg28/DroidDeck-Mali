package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The GPU safety switch, on a device that is neither Adreno nor Mali (Robolectric's): the parts
 * that do not depend on what the phone is. Software is software wherever it is asked for, and an
 * install that has never chosen gets the safest setting rather than whatever was there before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GpuSafetyTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun anInstallThatHasNeverChosenIsAuto() {
        assertEquals(GpuSafety.AUTO, GpuSafety.profile(app))
        assertEquals("auto", GpuSafety.profile(app))
    }

    @Test fun softwareIsAskedForOnItsOwn() {
        GpuSafety.setProfile(app, GpuSafety.SOFTWARE)
        assertTrue(GpuSafety.software(app))
        assertEquals(GpuSafety.SOFTWARE, GpuSafety.profile(app))
    }

    @Test fun theMaliSettingHandsTheGpuOver() {
        GpuSafety.setProfile(app, GpuSafety.MALI)
        assertFalse(GpuSafety.software(app))
        // Only where there is a Mali device at all: this one is neither.
        assertFalse(GpuSafety.maliDriverAllowed(app))
    }

    @Test fun anUnknownValueFallsBackToAuto() {
        app.getSharedPreferences("session", 0).edit().putString("gpuSafety", "anything").apply()
        assertEquals(GpuSafety.AUTO, GpuSafety.profile(app))
    }

    @Test fun softwareWithoutAnImportedSoftwareDriverDrawsWithNothing() {
        GpuSafety.setProfile(app, GpuSafety.SOFTWARE)
        // Nothing is imported here, so there is no software driver to fall back on: the session
        // keeps the runtime's own driver and says so, rather than drawing on the GPU on its own.
        assertEquals("", GpuSafety.linuxDriver(app))
    }

    @Test fun autoLeavesAnAdrenoChoiceAlone() {
        // Not a Mali device, so Auto does not touch the choice: whatever it is set to is what a
        // session draws with, exactly as before this switch existed.
        com.droiddeck.launcher.session.SessionPrefs.setLinuxDriver(app, "turnip-1")
        GpuSafety.setProfile(app, GpuSafety.AUTO)
        assertEquals("turnip-1", GpuSafety.linuxDriver(app))
    }
}
