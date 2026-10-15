package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException

/**
 * This fork's own driver downloads, offered directly in the GPU drivers tab in place of the
 * upstream Adreno (Turnip) release lists: a Mali phone has no use for a Turnip build, and the
 * drivers it does use are published right here, as release assets of this repository, by this
 * fork's own workflows.
 *
 *  - the PanVK-kbase universal package (mali-panvk-latest, rebuilt on every push of the driver
 *    workflow), the hardware driver a Mali session draws games with;
 *  - the lavapipe software package (software-mode-lavapipe), the CPU fallback.
 *
 * Both install through the same Linux-driver importer an imported zip uses, so a downloaded
 * driver and an imported one are the same thing afterwards.
 */
object MaliReleases {
    const val PANVK = "DroidDeck-Mali-CSF-PanVK-kbase-universal.zip"
    const val LAVAPIPE = "DroidDeck-Software-Vulkan-lavapipe.zip"
    private const val REPO = "jdmg28/DroidDeck-Mali"

    class Entry(
        val name: String,
        val url: String,
        val label: String,
        /** What the row says the driver is for. */
        val detail: Int,
    )

    fun all(): List<Entry> = listOf(
        Entry(PANVK,
              "https://github.com/$REPO/releases/download/mali-panvk-latest/$PANVK",
              "DroidDeck Mali · PanVK-kbase (universal)",
              R.string.mali_download_panvk_detail),
        Entry(LAVAPIPE,
              "https://github.com/$REPO/releases/download/software-mode-lavapipe/$LAVAPIPE",
              "DroidDeck Software Vulkan · lavapipe",
              R.string.mali_download_lavapipe_detail),
    )

    fun byName(name: String): Entry? = all().firstOrNull { it.name == name }

    fun isOurs(name: String): Boolean = byName(name) != null

    /** [entry] downloaded to the cache, with [progress] 0-100; the caller deletes the file. */
    fun download(context: Context, entry: Entry, progress: (Int) -> Unit): File {
        val target = File(context.cacheDir, entry.name)
        val c = URL(entry.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 120_000
        c.setRequestProperty("User-Agent", "DroidDeck-Mali-app")
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            // Release assets redirect to a signed CDN host; contentLength of the final response.
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        done += r
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        } finally {
            c.disconnect()
        }
        return target
    }
}
