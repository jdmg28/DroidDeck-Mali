package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.session.SessionFiles
import java.io.File

/**
 * What the Epic launcher's last session said on its way out, for the Epic page to show instead of
 * leaving the failure in a zip nobody has opened. Sessions live in day-stamped folders holding a
 * session.log whose first line names the mode and the program; the Epic runs say
 * "mode=run ... EpicGamesLauncher.AppImage". From those only lines that say why a program died are
 * surfaced - the log also carries X server noise (xkbcomp, PipeWire) and FEX's cosmetic
 * "Failed to remap /proc/pid/cmdline", which mean nothing on their own.
 */
object EpicDiagnostics {
    /** A line in an Epic session's log that says why the program never came up. */
    private val FATAL = listOf(
        "error while loading shared libraries",
        "sbrk() failure while processing tunables",
        "cannot open shared object file",
        "failed to load module",
        "segmentation fault",
        "glibc-detected",
        "out of memory",
        "primary child shut down",
        "x86 linux programs need",
        "does not exist in the guest",
        "is empty; pick it again",
        "bad interpreter",
    )

    /** Noise a dead session still leaves: FEX's cmdline remap needs a capability proot blocks. */
    private val COSMETIC = listOf("prctl failed", "xkbcomp", "pipewire", "keymap", "mesa")

    /** The first fatal line of the newest Epic session, or null when none ran or the last one lived. */
    fun lastError(context: Context): String? = lastError(
        listOfNotNull(
            runCatching { SessionFiles.logDirectory(context) }.getOrNull(),
            File(context.cacheDir, "session-logs"),
        ),
    )

    /** The same over the session folders' roots directly, so a JVM test can stage the logs. */
    fun lastError(roots: List<File>): String? {
        val folders = roots.flatMap { root ->
            root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        }.sortedByDescending { it.name }
        for (folder in folders.take(10)) {
            val log = File(folder, "session.log")
            if (!log.isFile || !isEpicSession(log)) continue
            // This is the newest Epic session: what it says is what the page reports. Bounded,
            // always - a dead session can leave a log of any size and this runs on the main
            // thread inside refresh(). The launcher's fatal lines show up early (it never gets
            // past them), so the first few thousand lines carry the whole story.
            val fatal = runCatching {
                log.useLines { lines ->
                    lines.take(4_000).firstOrNull { line ->
                        val lower = line.lowercase()
                        FATAL.any { lower.contains(it) } && COSMETIC.none { lower.contains(it) }
                    }
                }
            }.getOrNull()
            return fatal?.trim()?.take(180)
        }
        return null
    }

    /** Does this session belong to the Epic launcher? Said on the log's first line. */
    private fun isEpicSession(log: File) = runCatching {
        log.useLines { it.firstOrNull() }?.contains("EpicGamesLauncher", ignoreCase = true) == true
    }.getOrDefault(false)
}
