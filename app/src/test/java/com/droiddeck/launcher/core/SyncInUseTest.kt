package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * device.txt gains, at a session's end, the sync backend the session's games really ran on: wine
 * names it on its stderr ("... up and running."), the last marker wins, and a session where no
 * wineserver ever started says so instead of nothing.
 */
class SyncInUseTest {
    @get:Rule val tmp = TemporaryFolder()

    private val steamFsync = listOf(
        "== droiddeck-session mode=steam",
        "fsync: up and running.",
    )
    private val droiddeckFsync = listOf(
        "== droiddeck-session mode=steam",
        "droiddeck-fsync: up and running.",
        "fsync: up and running.",
    )
    private val packEsync = listOf(
        "== droiddeck-session mode=steam",
        "esync: up and running.",
        "droiddeck-esync: up and running.",
    )

    @Test
    @Test
    fun `the classifier answers the three pure cases`() {
        assertNull(DeviceReport.syncBackendInUse(listOf("== droiddeck-session mode=steam")))
        assertEquals("fsync (Proton's own, in-process)",
            DeviceReport.syncBackendInUse(listOf("== x", "fsync: up and running.")))
        assertEquals("esync through the pack (in-process)",
            DeviceReport.syncBackendInUse(listOf("== x", "esync: up and running.", "droiddeck-esync: up and running.")))
    }
}
