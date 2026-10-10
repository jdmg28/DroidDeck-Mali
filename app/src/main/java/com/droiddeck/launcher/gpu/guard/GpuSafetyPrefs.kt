/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu.guard

import android.content.Context
import android.os.SystemClock
import com.droiddeck.launcher.core.DeviceSupport

/**
 * The GPU guard's own settings, in their own preferences file.
 *
 * Defaults are chosen for the device this build exists for: on a Mali-G615 every switch below is
 * on, because that is the device where a Steam session ended in a kernel panic. On an Adreno the
 * guard is off until someone turns it on - it costs a little and protects against something that
 * has not been seen there.
 *
 * The history fields ([fatalBootCount], [cleanSessionsSinceFatal], [lockEngaged]) are not
 * preferences in the usual sense: they are the latch's memory, and they are what stops a phone
 * from walking back into the same crash over and over.
 */
object GpuSafetyPrefs {
    private const val FILE = "gpu_safety"

    /** No change to a session; the guard only watches and reports. */
    const val PROFILE_OFF = "off"

    /** The everyday setting for an experimental GPU: smaller output, a frame cap, nothing extra. */
    const val PROFILE_SAFE = "safe"

    /** What runs after the device has crashed once: the smallest session that still starts Steam. */
    const val PROFILE_STRICT = "strict"

    /** How many clean session ends in a row relax a lock the guard engaged by itself. */
    const val CLEAN_RUNS_TO_RELAX = 3

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The guard as a whole. Off, it does not watch, does not probe and does not block anything. */
    fun guardEnabled(context: Context): Boolean =
        prefs(context).getBoolean("guardEnabled", DeviceSupport.mali())

    fun setGuardEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("guardEnabled", on).apply()

    /** Whether a boot recorded as a crash blocks the next launch until the user re-arms. */
    fun lockAfterFatalBoot(context: Context): Boolean =
        prefs(context).getBoolean("lockAfterFatalBoot", true)

    fun setLockAfterFatalBoot(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("lockAfterFatalBoot", on).apply()

    /** A bounded GPU smoke test inside the runtime before gamescope and Steam are started. */
    /**
     * Whether a probe that could not be run blocks the launch, as one that failed does.
     *
     * On by default on the device this build exists for. A skip means the GPU was never asked, so
     * starting Steam is starting it blind - which is exactly how this phone came to reboot with a
     * guard installed and "passed" in its report. Blocking costs a session you then have to allow
     * on purpose (the `droiddeck-no-gpu-probe` file, or this switch); not blocking costs the
     * thing the guard is for.
     */
    fun probeRequired(context: Context): Boolean =
        prefs(context).getBoolean("probeRequired", DeviceSupport.mali())

    fun setProbeRequired(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("probeRequired", on).apply()

    fun preflightProbe(context: Context): Boolean =
        prefs(context).getBoolean("preflightProbe", true)

    fun setPreflightProbe(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("preflightProbe", on).apply()

    /**
     * Ask the driver package to write one line per GPU call into the session folder.
     *
     * Off by default and off for every device but this one: it costs a write() and an fsync per
     * submit and present, which is not nothing in a session that is already struggling, and it
     * only pays for itself while someone is trying to find out where a hang begins. It is the one
     * thing here that can tell a driver that blocks inside vkQueueSubmit apart from a GPU that
     * accepts the work and never finishes it - and those two want opposite fixes.
     */
    fun driverTrace(context: Context): Boolean =
        prefs(context).getBoolean("driverTrace", false)

    fun setDriverTrace(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("driverTrace", on).apply()

    /**
     * Whether the driver package tells clients about a DRM node for this GPU.
     *
     * On is what every session has been running with. It exists as a switch because it is the ONLY
     * thing the vendored adapter adds - everything else it does is pass the backend's answers
     * through unchanged - which makes it the single candidate on our side of the line between "the
     * driver is broken" and "our shim made the driver do something it was never tested doing". The
     * adapter answers `VK_EXT_physical_device_drm` with /dev/mali0, a kbase char device, and a
     * compositor that believes it is a DRM render node will go and treat it as one.
     *
     * Off means Steam very likely refuses to start: gamescope's nested Vulkan backend imports the
     * compositor's buffers through exactly this extension. That is an acceptable outcome here and
     * worth stating, because the alternative outcome on this device is a reboot.
     */
    fun drmFacade(context: Context): Boolean =
        prefs(context).getBoolean("drmFacade", true)

    fun setDrmFacade(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("drmFacade", on).apply()

    /** The live watchdog while a session runs: frames, wedged processes, temperature. */
    fun liveWatchdog(context: Context): Boolean =
        prefs(context).getBoolean("liveWatchdog", true)

    fun setLiveWatchdog(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("liveWatchdog", on).apply()

    /** How long the session may go without presenting a frame before the guard acts, in ms. */
    fun stallBudgetMs(context: Context): Int =
        prefs(context).getInt("stallBudgetMs", 5000).coerceIn(1000, 60_000)

    fun setStallBudgetMs(context: Context, ms: Int) =
        prefs(context).edit().putInt("stallBudgetMs", ms.coerceIn(1000, 60_000)).apply()

    /**
     * The battery temperature the guard acts on, in °C; 0 turns the thermal watch off. 44 is where
     * a phone in a hand is uncomfortably hot and well inside the skin-temperature limits the
     * platform itself enforces - the guard is meant to act before those, not after them.
     */
    fun thermalCeilingC(context: Context): Int =
        prefs(context).getInt("thermalCeilingC", 44).coerceIn(0, 60)

    fun setThermalCeilingC(context: Context, celsius: Int) =
        prefs(context).edit().putInt("thermalCeilingC", celsius.coerceIn(0, 60)).apply()

    /** The profile the user chose. What actually runs is [GpuGuard.profile], which the lock raises. */
    fun chosenProfile(context: Context): String =
        prefs(context).getString("profile", if (DeviceSupport.mali()) PROFILE_SAFE else PROFILE_OFF)
            ?: PROFILE_OFF

    fun setChosenProfile(context: Context, id: String) =
        prefs(context).edit().putString("profile", id).apply()

    /** Draw the Steam client's interface on the CPU (llvmpipe) instead of through the Mali Vulkan
     *  stack. Slow and ugly, and the reason it exists is diagnosis: if the fault goes away with it,
     *  it is in the Vulkan path and not in the client. */
    fun softwareUi(context: Context): Boolean = prefs(context).getBoolean("softwareUi", false)

    fun setSoftwareUi(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("softwareUi", on).apply()

    // ── The latch's memory ───────────────────────────────────────────────────────────────────

    /** Boots in a row (or since the start) that Android recorded as a crash. */
    fun fatalBootCount(context: Context): Int = prefs(context).getInt("fatalBootCount", 0)

    fun setFatalBootCount(context: Context, count: Int) =
        prefs(context).edit().putInt("fatalBootCount", count.coerceAtLeast(0)).apply()

    fun lastFatalBootAt(context: Context): Long = prefs(context).getLong("lastFatalBootAt", 0L)

    fun setLastFatalBootAt(context: Context, at: Long) =
        prefs(context).edit().putLong("lastFatalBootAt", at).apply()

    fun lastFatalBootReason(context: Context): String =
        prefs(context).getString("lastFatalBootReason", "") ?: ""

    fun setLastFatalBootReason(context: Context, reason: String) =
        prefs(context).edit().putString("lastFatalBootReason", reason).apply()

    /** Session ends the guard forced, ever. Evidence for "this is not a one-off". */
    fun guardStops(context: Context): Int = prefs(context).getInt("guardStops", 0)

    private fun setGuardStops(context: Context, count: Int) =
        prefs(context).edit().putInt("guardStops", count.coerceAtLeast(0)).apply()

    fun countGuardStop(context: Context) = setGuardStops(context, guardStops(context) + 1)

    /** Sessions that ended on their own (a clean quit) since the last crash. */
    fun cleanSessionsSinceFatal(context: Context): Int =
        prefs(context).getInt("cleanSessionsSinceFatal", 0)

    fun setCleanSessionsSinceFatal(context: Context, count: Int) =
        prefs(context).edit().putInt("cleanSessionsSinceFatal", count.coerceAtLeast(0)).apply()

    /** The lock: a launch is refused until the user re-arms, or until [CLEAN_RUNS_TO_RELAX] clean runs. */
    fun lockEngaged(context: Context): Boolean = prefs(context).getBoolean("lockEngaged", false)

    fun setLockEngaged(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("lockEngaged", on).apply()

    /** The last pre-flight probe, for the page and the device report. */
    fun lastProbeAt(context: Context): Long = prefs(context).getLong("lastProbeAt", 0L)

    fun lastProbeOk(context: Context): Boolean = prefs(context).getBoolean("lastProbeOk", false)

    fun lastProbeSummary(context: Context): String =
        prefs(context).getString("lastProbeSummary", "") ?: ""

    /**
     * The verdict line the probe printed (`DD_GPU_PROBE_OK` / `_SKIPPED` / `_FAILED` / ...).
     * Kept apart from [lastProbeOk] because "did not block" and "was actually run" are different
     * questions: a skipped probe is allowed through by design, and reporting it as a pass is how
     * a session ends up starting with no GPU test behind it at all.
     */
    fun lastProbeVerdict(context: Context): String =
        prefs(context).getString("lastProbeVerdict", "") ?: ""

    fun setLastProbe(context: Context, ok: Boolean, summary: String, verdict: String = "") =
        prefs(context).edit()
            .putLong("lastProbeAt", System.currentTimeMillis())
            .putBoolean("lastProbeOk", ok)
            .putString("lastProbeSummary", summary.take(2000))
            .putString("lastProbeVerdict", verdict.take(200))
            .apply()

    /**
     * `elapsedRealtime()` at the app's last start. It only ever rises within a boot, so a value
     * behind the one on file means the phone has rebooted since - which is how the guard counts one
     * crash once instead of once per app start. Kept out of [reset]: it is not evidence, it is a
     * clock, and clearing it would make the next start look like a new boot.
     */
    fun lastSeenUptime(context: Context): Long = prefs(context).getLong("lastSeenUptime", -1L)

    fun setLastSeenUptime(context: Context, uptimeMillis: Long) =
        prefs(context).edit().putLong("lastSeenUptime", uptimeMillis).apply()

    /**
     * A session that started and was never recorded as ending.
     *
     * This is the guard's own witness, and it is the one that matters on a phone whose boot reason
     * cannot be read. It needs no property, no permission and no hidden API: uptime only ever
     * moves forward except across a reboot, so an uptime now that is behind the uptime the session
     * started at, or behind where the wall clock says it should be, is a reboot, proved.
     */
    class OpenSession(
        /** The session folder's name: what to look for in the logs. */
        val id: String,
        /** UTC wall clock at start, to say how long the session had been going. */
        val wallMs: Long,
        /** `elapsedRealtime()` at start, which only resets when the phone restarts. */
        val uptimeMs: Long,
        val profile: String,
    )

    fun openSession(context: Context): OpenSession? {
        val id = prefs(context).getString("openSessionId", "") ?: ""
        if (id.isEmpty()) return null
        val store = prefs(context)
        return OpenSession(
            id,
            store.getLong("openSessionWall", 0L),
            store.getLong("openSessionUptime", 0L),
            store.getString("openSessionProfile", "") ?: "",
        )
    }

    /** Called the moment a session starts, and the only thing that makes the check below possible. */
    fun markSessionOpen(context: Context, id: String, profile: String) = prefs(context).edit()
        .putString("openSessionId", id)
        .putLong("openSessionWall", System.currentTimeMillis())
        .putLong("openSessionUptime", SystemClock.elapsedRealtime())
        .putString("openSessionProfile", profile)
        .apply()

    /** The session ended and the app saw it end, so it stops being evidence of anything. */
    fun clearOpenSession(context: Context) = prefs(context).edit()
        .remove("openSessionId")
        .apply()

    /**
     * The last session the app saw end. Kept alongside [OpenSession] because an end is evidence
     * too: the guard's own abort is the last thing it records before the phone reboots, and if the
     * only mark that survives is "no session open", the crash looks clean and the next attempt is
     * free. That is exactly how a real panic on a real device got past the latch on 2026-10-10:
     * the watchdog stopped the session at 2.2 s, the device restarted 48 s later, and the guard
     * found nothing open to blame it on.
     */
    class SessionEnd(
        val id: String,
        /** Whether the session finished on its own, with no fault and no abort. */
        val normally: Boolean,
        /** Whether the guard was the thing that stopped it. */
        val byGuard: Boolean,
        val wallMs: Long,
        val uptimeMs: Long,
    )

    fun lastSessionEnd(context: Context): SessionEnd? {
        val store = prefs(context)
        val id = store.getString("lastEndId", "") ?: ""
        if (id.isEmpty()) return null
        return SessionEnd(
            id,
            store.getBoolean("lastEndNormally", false),
            store.getBoolean("lastEndByGuard", false),
            store.getLong("lastEndWall", 0L),
            store.getLong("lastEndUptime", 0L),
        )
    }

    fun markSessionEnd(context: Context, id: String, normally: Boolean, byGuard: Boolean) =
        prefs(context).edit()
            .putString("lastEndId", id)
            .putBoolean("lastEndNormally", normally)
            .putBoolean("lastEndByGuard", byGuard)
            .putLong("lastEndWall", System.currentTimeMillis())
            .putLong("lastEndUptime", SystemClock.elapsedRealtime())
            .apply()

    /** An end is counted once. Whatever it said, it is not evidence about a second restart. */
    fun clearSessionEnd(context: Context) = prefs(context).edit()
        .remove("lastEndId")
        .apply()

    /** Clears the crash history and the lock. The counters are evidence; this throws it away. */
    fun reset(context: Context) = prefs(context).edit()
        .putInt("fatalBootCount", 0)
        .putLong("lastFatalBootAt", 0L)
        .putString("lastFatalBootReason", "")
        .putInt("cleanSessionsSinceFatal", 0)
        .putBoolean("lockEngaged", false)
        .apply()
}
