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
    fun `the last marker wins`() {
        // A game that turned esync off falls back down the chain mid-session.
        val lines = packEsync + listOf("game said no", "droiddeck-fsync: up and running.", "fsync: up and running.")
        assertEquals("fsync (Proton's own, in-process)", DeviceReport.syncBackendInUse(lines))
    }

    @Test
    fun `each backend is named after what reported it`() {
        assertEquals("droiddeck-fsync (in-process)", DeviceReport.syncBackendInUse(droiddeckFsync))
        assertEquals("esync through the pack (in-process)", DeviceReport.syncBackendInUse(packEsync))
        assertEquals("ntsync through the pack (in-process)",
            DeviceReport.syncBackendInUse(listOf("droiddeck-ntsync: up and running.")))
        assertEquals("ntsync (kernel driver)",
            DeviceReport.syncBackendInUse(listOf("wine: /dev/ntsync up and running")))
        assertEquals("esync (wine's own, in-process)", DeviceReport.syncBackendInUse(listOf("esync: up and running.")))
    }

    @Test
    fun `a session where wineserver never started says so`() {
        assertNull(DeviceReport.syncBackendInUse(steamFsync.take(1)))
        assertEquals("fsync (Proton's own, in-process)", DeviceReport.syncBackendInUse(steamFsync))
    }

    @Test
    fun `the finished report carries the section`() {
        val dir = tmp.root
        val device = dir.resolve("device.txt").apply { writeText("DroidDeck session report\nWine sync              fsync\n") }
        dir.resolve("session.log").writeText((packEsync + listOf("noise")).joinToString("\n"))
        DeviceReport.appendSyncInUse(device, dir.resolve("session.log"))
        val text = device.readText()
        assertEquals(true, text.contains("Sync in use"))
        assertEquals(true, text.contains("esync through the pack (in-process)"))
    }

    @Test
    fun `a missing log still leaves a readable answer`() {
        val device = tmp.root.resolve("device.txt").apply { writeText("report\n") }
        DeviceReport.appendSyncInUse(device, null)
        assertEquals(true, device.readText().contains("no wineserver started"))
        val device2 = tmp.root.resolve("device2.txt").apply { writeText("report\n") }
        DeviceReport.appendSyncInUse(device2, File(tmp.root, "gone.log"))
        assertEquals(true, device2.readText().contains("no wineserver started"))
    }
}
