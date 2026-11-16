package com.droiddeck.launcher.gpu

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.session.SessionPrefs

/**
 * A Mali phone, set up for its first start without the user finding the drivers tab: the GPU-safety
 * switch is turned to Mali (Auto keeps the GPU out of the guest), and only when nothing else can
 * draw with the GPU is this repository's own PanVK package downloaded and selected. The official
 * driver pairs are left alone: 0.7.1 installs and picks those itself.
 *
 * Once (a stored flag; a failure retries the next start), and only on a device the Mali detection
 * recognises. Anything it needs again lives in the drivers tab.
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
                val chosen = SessionPrefs.linuxDriver(app)
                // A driver already chosen, or a pair the release check found for this GPU, is
                // what 0.7.1's Auto mode draws with: leave it alone.
                val pair = DriverPairs.recommendedKey(GpuInfo.detect(), DriverPairs.from(TurnipReleases.cached(app)))
                // The GPU is only handed to the guest once something can actually draw with it.
                // Auto (no GPU node in the guest) is the safe state when nothing can, and it is
                // what a phone that cannot download the package keeps.
                var draws = installed || chosen.isNotEmpty() || pair != null
                if (!draws) {
                    val entry = MaliReleases.byName(MaliReleases.PANVK) ?: return@Thread
                    val file = MaliReleases.download(app, entry) { }
                    val id = try {
                        LinuxVulkanDriverManager(app).installDriver(Uri.fromFile(file), entry.name)
                    } finally {
                        file.delete()
                    }
                    SessionPrefs.setLinuxDriver(app, id)
                    SessionPrefs.setAndroidDriver(app, TurnipDriver.AUTO)
                    SessionPrefs.setMaliDownloaded(app, SessionPrefs.maliDownloaded(app) + entry.name)
                    draws = true
                    Log.i(TAG, "the bundled driver setup installed and selected $id")
                } else {
                    Log.i(TAG, "auto setup: leaving the driver to Auto (installed=$installed, chosen=$chosen, pair=$pair)")
                }
                if (draws && GpuSafety.profile(app) == GpuSafety.AUTO) GpuSafety.setProfile(app, GpuSafety.MALI)
                done.edit().putBoolean("done", true).apply()
            } catch (e: Exception) {
                // Undone on purpose: no network this start, or the release missing - the next
                // start tries again, and the drivers tab always offers the same packages by hand.
                Log.w(TAG, "auto setup: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "mali-autosetup").start()
    }
}
