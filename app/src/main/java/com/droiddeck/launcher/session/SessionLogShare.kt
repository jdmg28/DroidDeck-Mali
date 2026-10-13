package com.droiddeck.launcher.session

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zips the most recent session's log folder and hands it to Android's share sheet. Works where
 * the folder could not be written to Downloads (no storage permission, or a device that refuses
 * it) and landed in the app's private files instead, which a user cannot otherwise reach.
 */
object SessionLogShare {
    private val main = Handler(Looper.getMainLooper())

    /**
     * How far the zip for a share has got, 0..1, or null when none is being made. The Share logs
     * buttons show it in place of their label, so a tap is answered at once.
     */
    var progress by mutableStateOf<Float?>(null)
        private set

    /**
     * Zips [folder] on a worker thread with [progress] following along, then hands the zip (null
     * if there was nothing to zip or it failed) to [done] on the main thread. Call on the main
     * thread; a tap while a zip is being made is ignored.
     */
    fun prepare(context: Context, folder: () -> File?, done: (File?) -> Unit) {
        if (progress != null) return
        progress = 0f
        Thread({
            val zip = runCatching { folder()?.let { dir -> zipFolder(context, dir) { p -> main.post { if (progress != null) progress = p } } } }
                .onFailure { Log.w("SessionLogShare", "could not package session logs", it) }
                .getOrNull()
            main.post { progress = null; done(zip) }
        }, "share-logs").start()
    }

    /** The newest session folder in either place logs are written, or null if there is none. */
    fun latest(context: Context): File? = SessionPaths.sessionFolders(context).lastOrNull()

    /** Builds the zip (blocking). Returns null when there is no session to share. */

    /** Builds a zip for one specific session folder (blocking). */
    fun zipFolder(context: Context, folder: File, onProgress: ((Float) -> Unit)? = null): File? {
        if (!folder.isDirectory) return null
        // A log in steam/ past STEAM_LOG_MAX_BYTES is trimmed to its tail rather than left out, as
        // it is from the app's own copy: a CEF debug log or an old *.previous.txt reached 17 MB and
        // scrubbing them line by line kept the share sheet from appearing for ten seconds. Proton's
        // log is the other half of that - it is the only thing that says why a game stopped, and it
        // says it at the end.
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return null
        val out = File(context.cacheDir, "shared-logs").apply { deleteRecursively(); mkdirs() }
        val zip = File(out, "DroidDeck-${folder.name}.zip")
        val tails = trimmedTails(context, files)
        // Scrubbed on the way into the zip: a session shared while it runs has not had its end-of-
        // session pass yet, and the redactor changes nothing in a line that is already clean.
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        // A finished folder was scrubbed whole when it ended. A file its record lists, unchanged
        // since, goes in as it is; anything else (added or changed since, hidden, or any file of a
        // session still running) is scrubbed again. Scrubbing tens of MB of the client's logs on
        // every share kept the share sheet from appearing for ten seconds or more.
        val scrubbed = SessionArtifacts.scrubbedFiles(folder)
        val live = liveSteamLogs(context, folder)
        // The stores' own log (last 7 days, app-private, already redacted), under stores/.
        val storeLogs = com.droiddeck.launcher.stores.StoreLogFiles.files(context)
        val total = (files + live + storeLogs).sumOf { it.length() }.coerceAtLeast(1L)
        var done = 0L
        var shown = -1
        fun advance(f: File) {
            done += f.length()
            val percent = (done * 100 / total).toInt()
            if (percent != shown) { shown = percent; onProgress?.invoke(percent / 100f) }
        }
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            files.forEach { f ->
                val src = tails[f] ?: f
                val path = f.relativeTo(folder).path
                addEntry(z, folder.name + "/" + path, src, src === f && path in scrubbed)
                advance(f)
            }
            live.forEach { f -> addEntry(z, folder.name + "/steam/" + f.name, f, false); advance(f) }
            storeLogs.forEach { f -> addEntry(z, folder.name + "/stores/" + f.name, f, false); advance(f) }
        }
        return zip
    }

    /**
     * For every log in steam/ that is over [STEAM_LOG_MAX_BYTES], a copy of its last bytes, keyed
     * by the original. The cut is moved forward to the next newline, so what goes in the zip starts
     * on a line of its own and says what was left out. A file that cannot be read is left alone.
     */
    private fun trimmedTails(context: Context, files: List<File>): Map<File, File> {
        val out = File(context.cacheDir, "shared-logs").apply { mkdirs() }
        val tails = LinkedHashMap<File, File>()
        files.forEach { f ->
            if (f.parentFile?.name != "steam" || f.length() <= STEAM_LOG_MAX_BYTES) return@forEach
            runCatching {
                val over = f.length() - STEAM_LOG_MAX_BYTES
                val bytes = java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(over)
                    ByteArray(STEAM_LOG_MAX_BYTES.toInt()).also { raf.readFully(it) }
                }
                val start = bytes.indexOf('\n'.code.toByte()).let { if (it >= 0) it + 1 else 0 }
                File(out, "tail-${f.name}").apply {
                    writeBytes(bytes.copyOfRange(start, bytes.size))
                    appendText("... (this log was trimmed: its first ${over / 1024} KB were left out)\n")
                }
            }.onSuccess { copy -> tails[f] = copy }
             .onFailure { Log.w("SessionLogShare", "could not trim ${f.name}", it) }
        }
        return tails
    }

    private fun addEntry(z: ZipOutputStream, name: String, f: File, scrubbed: Boolean) {
        z.putNextEntry(ZipEntry(name))
        if (!scrubbed && LogRedactor.isText(f)) {
            val w = z.bufferedWriter()
            LogRedactor.scrubTo(f, w)
            w.flush()
        } else {
            f.inputStream().use { it.copyTo(z) }
        }
        z.closeEntry()
    }

    /**
     * The client's own logs as they stand, for a session shared while it runs. The session script
     * copies them into steam/ only as it exits, so a zip made from the drawer had none - and
     * controller.txt (which pad the client opened, the touch mode it set) is what a controller or
     * touch report needs most. Only for the running session: an older folder would get this
     * session's logs. The same files the script copies; nothing holding credentials is in logs/.
     */
    private fun liveSteamLogs(context: Context, folder: File): List<File> {
        if (folder != SessionPaths.current() || File(folder, "steam").exists()) return emptyList()
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        return logs.listFiles()?.filter { it.isFile && it.length() <= STEAM_LOG_MAX_BYTES }.orEmpty()
    }

    /** The client's content and bootstrap logs grow large over months and say nothing about a session. */
    private const val STEAM_LOG_MAX_BYTES = 8L * 1024 * 1024

    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(zip.name, uri)
        return Intent.createChooser(send, context.getString(R.string.logshare_chooser)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
