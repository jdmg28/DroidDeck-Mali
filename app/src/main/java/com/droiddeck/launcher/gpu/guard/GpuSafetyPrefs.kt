/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu.guard

import android.content.Context
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

    fun setLastProbe(context: Context, ok: Boolean, summary: String) =
        prefs(context).edit()
            .putLong("lastProbeAt", System.currentTimeMillis())
            .putBoolean("lastProbeOk", ok)
            .putString("lastProbeSummary", summary.take(2000))
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

    /** Clears the crash history and the lock. The counters are evidence; this throws it away. */
    fun reset(context: Context) = prefs(context).edit()
        .putInt("fatalBootCount", 0)
        .putLong("lastFatalBootAt", 0L)
        .putString("lastFatalBootReason", "")
        .putInt("cleanSessionsSinceFatal", 0)
        .putBoolean("lockEngaged", false)
        .apply()
}
