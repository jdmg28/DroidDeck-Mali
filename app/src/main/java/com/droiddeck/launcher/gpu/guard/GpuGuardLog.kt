/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu.guard

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The guard's record: one append-only file in the app's own files directory that survives
 * everything, and a copy inside the session folder while a session runs.
 *
 * It exists because the one thing a kernel panic takes with it is evidence. The app is not running
 * when the GPU goes, so whatever it knew before that - the boot reason it started on, the profile
 * it applied, the samples the watchdog was seeing in the seconds before - has to already be on
 * disk. `Download/DroidDeck/<session>/gpu-guard.log` is what a bug report should be attached with.
 *
 * Every line also goes to logcat, so a run can be followed from a computer without pulling files.
 */
object GpuGuardLog {
    private const val TAG = "GpuGuard"
    private const val FILE = "gpu-guard.log"
    /** The persistent copy is trimmed back to this, oldest lines first, so it cannot grow forever. */
    private const val KEEP_BYTES = 96 * 1024

    private val lock = Any()

    @Volatile
    private var persistent: File? = null

    @Volatile
    private var session: File? = null

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Called once, from the app's start: where the persistent copy lives. */
    fun bind(context: android.content.Context) {
        synchronized(lock) {
            persistent = File(context.applicationContext.filesDir, FILE)
        }
    }

    /** A session started: its folder gets its own copy from here on. */
    fun openSession(dir: File?, header: List<String> = emptyList()) {
        synchronized(lock) {
            session = dir?.let { File(it, FILE) }
        }
        if (dir == null) return
        log("guard", "── session folder ${dir.name} ──")
        header.forEach { log("guard", it) }
    }

    fun closeSession(reason: String) {
        log("guard", "── session ended: $reason ──")
        synchronized(lock) { session = null }
    }

    /** One line, timestamped, to the session copy (when there is one), the persistent copy and logcat. */
    fun log(tag: String, message: String) {
        val line = "${stamp.format(Date())} $tag: $message\n"
        Log.i(TAG, "$tag: $message")
        val targets = synchronized(lock) { listOfNotNull(session, persistent) }
        for (file in targets) {
            try {
                file.parentFile?.mkdirs()
                append(file, line)
            } catch (e: Exception) {
                // A log that cannot be written must never take the session with it.
            }
        }
        trim()
    }

    /**
     * Appends one line and forces it to the storage.
     *
     * `File.appendText` is not enough, and the difference is the entire point of this file. A
     * plain write reaches the kernel's page cache and returns; the bytes reach the storage when
     * the writeback timer gets to them, which on Android is up to thirty seconds later. A kernel
     * panic takes the page cache with it, so every line written in the seconds before one is
     * simply gone - and the log then looks as though the watchdog stopped sampling, when in truth
     * it was sampling until the device died. That is what the first crash on the Mali-G615 looked
     * like: samples every four seconds up to 22:33:06, then nothing, and a reboot recorded at
     * 22:34:23 with a log that ended a minute and a half before it.
     *
     * An fsync per line costs microseconds on a file this quiet, and it puts the last line before
     * a panic where it can actually be read afterwards.
     */
    private fun append(file: File, line: String) {
        FileOutputStream(file, true).use { stream ->
            stream.write(line.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    /** The persistent copy's tail, newest last, for the settings page and the device report. */
    fun read(context: android.content.Context, lines: Int = 120): String {
        val file = File(context.applicationContext.filesDir, FILE)
        val text = try {
            if (file.isFile) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
        if (text.isEmpty()) return ""
        val all = text.split('\n')
        val take = lines.coerceIn(1, 2000)
        return all.drop((all.size - take).coerceAtLeast(0)).joinToString("\n").trim()
    }

    private fun trim() {
        val file = synchronized(lock) { persistent } ?: return
        try {
            if (!file.isFile || file.length() <= KEEP_BYTES) return
            val bytes = file.readBytes()
            val from = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
            var start = from
            // Cut on a line boundary so the file never opens with half a line.
            while (start < bytes.size && bytes[start].toInt() != '\n'.code) start++
            if (start >= bytes.size) return
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(bytes.copyOfRange(start + 1, bytes.size))
            if (file.delete()) tmp.renameTo(file) else tmp.delete()
        } catch (e: Exception) {
            // Trimming is housekeeping; failing it only means the file is a little longer.
        }
    }
}
