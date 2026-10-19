package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stands between the "Steam interface on the GPU" switch and the failure it is
 * known for on Mali. Off Mali nothing changes; on Mali only the driver built here with the
 * provoking-vertex address fix (named "fn64") is allowed to carry the browser on the GPU.
 */
class SteamUiGpuAllowedTest {

    private val fn64 = "DroidDeck Mali CSF PanVK-kbase r7 fn64 Steam UI fix (universal)"
    private val fn64Version = "Adapter 0.2.0 / PanVK-kbase rc3 + fn64 fix / Mesa 26.3.0-devel"
    private val rc3 = "DroidDeck Mali CSF PanVK-kbase v0.1.0-rc3-lowmem (universal)"
    private val rc3Version = "Adapter 0.2.0 / PanVK-kbase v0.1.0-rc3-lowmem / Mesa 26.3.0-devel"

    @Test fun `off Mali any driver may run the browser on the GPU`() {
        assertTrue(SessionPrefs.steamUiGpuAllowed(false, rc3, rc3Version))
        assertTrue(SessionPrefs.steamUiGpuAllowed(false, "", ""))
    }

    @Test fun `on Mali the imported rc3 is refused`() {
        assertFalse(SessionPrefs.steamUiGpuAllowed(true, rc3, rc3Version))
    }

    @Test fun `on Mali the built driver is allowed by name or by version`() {
        assertTrue(SessionPrefs.steamUiGpuAllowed(true, fn64, ""))
        assertTrue(SessionPrefs.steamUiGpuAllowed(true, "some driver", fn64Version))
    }

    @Test fun `the runtime's own driver is refused on Mali`() {
        assertFalse(SessionPrefs.steamUiGpuAllowed(true, "", ""))
    }

    @Test fun `the marker is matched case-insensitively`() {
        assertTrue(SessionPrefs.steamUiGpuAllowed(true, "PanVK-kbase r7 FN64", ""))
    }
}
