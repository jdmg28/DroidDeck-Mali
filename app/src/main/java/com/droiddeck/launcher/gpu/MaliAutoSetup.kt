package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.session.SessionPrefs

/**
 * The Mali driver, installed and set on a Mali phone without the user finding the drivers tab:
 * on the first start the universal PanVK-kbase package is downloaded from this repository's
 * release, installed through the same importer a picked zip uses, made the runtime driver, and
 * the GPU-safety switch turned to Mali - the state a user who picked it in the drivers tab would
 * end at. Once (a stored flag; a failure retries the next start), and only on a device the Mali
 * detection recognises. Anything it needs again lives in the drivers tab.
 */
object MaliAutoSetup {
    private const val TAG = "MaliAutoSetup"

    fun ensure(context: Context) {
        if (!DeviceSupport.mali()) return
        val app = context.applicationContext
        val done = app.getSharedPreferences("mali.autosetup", Context.MODE_PRIVATE)
        if (done.getBoolean("done", false)) return
        Thread({
            try {
                val manager = LinuxVulkanDriverManager(app)
                val installed = manager.enumerateInstalledDrivers().any { manager.isMaliRuntimeDriver(it) }
                if (!installed) {
                    val entry = MaliReleases.byName(MaliReleases.PANVK) ?: return@Thread
                    val file = MaliReleases.download(app, entry) { }
                    val id = try {
                        LinuxVulkanDriverManager(app).installDriver(Uri.fromFile(file), entry.name)
                    } finally {
                        file.delete()
                    }
                    SessionPrefs.setLinuxDriver(app, id)
                    SessionPrefs.setAndroidDriver(app, TurnipDriver.AUTO)
                    GpuSafety.setProfile(app, GpuSafety.MALI)
                    SessionPrefs.setMaliDownloaded(app, SessionPrefs.maliDownloaded(app) + entry.name)
                    Log.i(TAG, "the bundled driver setup installed and selected $id")
                }
                done.edit().putBoolean("done", true).apply()
            } catch (e: Exception) {
                // Undone on purpose: no network this start, or the release missing - the next
                // start tries again, and the drivers tab always offers the same packages by hand.
                Log.w(TAG, "auto setup: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "mali-autosetup").start()
    }
}
