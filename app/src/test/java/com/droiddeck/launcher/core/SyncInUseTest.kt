package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure half: which backend a session log's "up and running" markers name, last one wins. */
class SyncInUseTest {
    @Test
    fun `no marker at all is null`() {
        assertNull(DeviceReport.syncBackendInUse(listOf("== droiddeck-session mode=steam")))
        assertNull(DeviceReport.syncBackendInUse(emptyList()))
    }

    @Test
    fun `each backend is named after what reported it`() {
        assertEquals("fsync (Proton's own, in-process)",
            DeviceReport.syncBackendInUse(listOf("== x", "fsync: up and running.")))
        assertEquals("droiddeck-fsync (in-process)",
            DeviceReport.syncBackendInUse(listOf("droiddeck-fsync: up and running.", "fsync: up and running.")))
        assertEquals("esync through the pack (in-process)",
            DeviceReport.syncBackendInUse(listOf("esync: up and running.", "droiddeck-esync: up and running.")))
        assertEquals("ntsync through the pack (in-process)",
            DeviceReport.syncBackendInUse(listOf("droiddeck-ntsync: up and running.")))
        assertEquals("ntsync (kernel driver)",
            DeviceReport.syncBackendInUse(listOf("wine: /dev/ntsync up and running")))
        assertEquals("esync (wine's own, in-process)",
            DeviceReport.syncBackendInUse(listOf("esync: up and running.")))
    }

    @Test
    fun `the last marker wins`() {
        val lines = listOf("esync: up and running.", "droiddeck-esync: up and running.",
            "game said no", "droiddeck-fsync: up and running.", "fsync: up and running.")
        assertEquals("fsync (Proton's own, in-process)", DeviceReport.syncBackendInUse(lines))
    }
}
