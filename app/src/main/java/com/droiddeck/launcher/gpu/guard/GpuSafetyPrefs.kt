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
    fun preflightProbe(context: Context): Boolean =
        prefs(context).getBoolean("preflightProbe", true)

    fun setPreflightProbe(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("preflightProbe", on).apply()

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

    /** Clears the crash history and the lock. The counters are evidence; this throws it away. */
    fun reset(context: Context) = prefs(context).edit()
        .putInt("fatalBootCount", 0)
        .putLong("lastFatalBootAt", 0L)
        .putString("lastFatalBootReason", "")
        .putInt("cleanSessionsSinceFatal", 0)
        .putBoolean("lockEngaged", false)
        .apply()
}
