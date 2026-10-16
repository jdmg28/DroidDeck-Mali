package com.droiddeck.launcher.frontend

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.zip.ZipFile

/**
 * Import a game the stores do not carry: a downloaded .zip (GOG offline-style folders, itch.io
 * builds, repacks) extracted straight into the user's games folder, where AddedGames already
 * scans, picks the .exe and hands the game to the Steam client as a shortcut under Proton+FEX.
 * Nothing else to configure - the session does the rest at its next start.
 */
object GameImport {
    class ImportException(message: String) : Exception(message)

    /**
     * The folder imports land in: the first chosen games folder, or Download/DroidDeck-Games,
     * created and chosen on the spot so a first import needs no setup.
     */
    fun targetDir(context: Context): File {
        SessionPrefs.addedGamesDirs(context).firstOrNull()?.let { dir ->
            File(dir).mkdirs()
            if (File(dir).isDirectory) return File(dir)
        }
        val dir = File(Environment.getExternalStorageDirectory(), "Download/DroidDeck-Games")
        if (!dir.isDirectory && !dir.mkdirs()) throw ImportException("cannot create ${dir.path}")
        SessionPrefs.setAddedGamesDirs(context, (SessionPrefs.addedGamesDirs(context) + dir.path).distinct())
        return dir
    }

    /**
     * Extract [zip] into its own folder under [targetDir], named after the zip. Returns the
     * folder created. Entry names that leave the folder ("../"), absolute paths and the
     * archive's own bookkeeping (__MACOSX, AppleDouble) are skipped or rejected.
     */
    fun importZip(context: Context, zip: File): File {
        if (!zip.isFile) throw ImportException("no such file: $zip")
        val base = targetDir(context)
        val name = zip.nameWithoutExtension.replace(Regex("[/\\\\:<>|\"?*]"), "_").trim().ifEmpty { "game" }
        var folder = File(base, name)
        var n = 2
        while (folder.exists()) folder = File(base, "$name-$n").also { n++ }
        if (!folder.mkdirs()) throw ImportException("cannot create ${folder.path}")
        val inside = folder.canonicalPath + File.separator
        try {
            ZipFile(zip).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name
                    if (entryName.isEmpty()) continue
                    val fileName = entryName.substringAfterLast('/')
                    if (entryName.startsWith("__MACOSX/") || entryName.contains("/__MACOSX/") || fileName.startsWith("._")) continue
                    val out = File(folder, entryName)
                    if (!out.canonicalPath.startsWith(inside) || out.canonicalPath == folder.canonicalPath) {
                        throw ImportException("the archive escapes its game folder: $entryName")
                    }
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        archive.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output, 1 shl 18) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
        return folder
    }
}
