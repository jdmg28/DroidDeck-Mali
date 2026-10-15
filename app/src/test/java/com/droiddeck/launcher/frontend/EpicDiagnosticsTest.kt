package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Epic page reads the launcher's failure off the newest Epic session's log: an Epic session
 * is told by its first line, a fatal line by the patterns - and the log's noise (FEX's prctl
 * remap, the X server's keymaps) never counts as one.
 */
class EpicDiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun session(name: String, firstLine: String, vararg lines: String) =
        tmp.newFolder(name).resolve("session.log").apply {
            writeText((listOf(firstLine) + lines).joinToString(separator = "\n", postfix = "\n"))
        }

    @Test
    fun `the newest epic session's fatal line is the answer`() {
        session("2026-10-14-01-run", "== droiddeck-session mode=steam", "noise", "primary child shut down")
        session("2026-10-15-03-run", "== droiddeck-session mode=run /opt/appimages/EpicGamesLauncher.AppImage",
            "E Failed to remap /proc/pid/cmdline data (prctl failed: result -1, errno 1)",
            "sbrk() failure while processing tunables",
            "[gamescope] launch: Primary child shut down!")
        assertEquals("sbrk() failure while processing tunables",
            EpicDiagnostics.lastError(listOf(tmp.root)))
    }

    @Test
    fun `a non-epic newest session is skipped for an older epic one`() {
        session("2026-10-15-05-steam", "== droiddeck-session mode=steam", "everything fine")
        session("2026-10-15-04-run", "== droiddeck-session mode=run /opt/appimages/EpicGamesLauncher.AppImage",
            "error while loading shared libraries: libwebkit2gtk-4.1.so.0")
        assertEquals("error while loading shared libraries: libwebkit2gtk-4.1.so.0",
            EpicDiagnostics.lastError(listOf(tmp.root)))
    }

    @Test
    fun `an epic session that lived reports nothing`() {
        session("2026-10-15-06-run", "== droiddeck-session mode=run /opt/appimages/EpicGamesLauncher.AppImage",
            "[gamescope] Info: xwm: Embedded", "== clipboard: Android text available in Xwayland")
        assertNull(EpicDiagnostics.lastError(listOf(tmp.root)))
    }

    @Test
    fun `no epic session at all is quiet`() {
        session("2026-10-15-07-steam", "== droiddeck-session mode=steam", "primary child shut down")
        assertNull(EpicDiagnostics.lastError(listOf(tmp.root)))
    }
}
