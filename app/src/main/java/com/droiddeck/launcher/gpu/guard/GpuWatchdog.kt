/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu.guard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File

/**
 * Watches a running session for the two things that come before a GPU that stops answering.
 *
 * An APK cannot make the Mali kernel module more robust. What it can do is notice that the session
 * has stopped moving and take it down itself, in the app's own time, rather than leaving the
 * kernel to discover the same thing three seconds later on a path that ends in a panic.
 *
 * Two independent signals, because either alone would cry wolf:
 *
 * - **Frames.** The compositor records the interval between presented frames
 *   ([WaylandCompositor.recentFrameIntervalMs]). gamescope presents continuously, so a gap of
 *   many seconds is not "a heavy scene", it is a session that has stopped.
 * - **Wedged processes.** A GPU that has stopped completing jobs leaves the threads waiting on it
 *   in uninterruptible sleep (`D` in /proc). A momentary `D` is normal I/O; several of our own
 *   processes sitting in it across consecutive samples is a fence that is never going to be
 *   signalled.
 *
 * Plus temperature, which is not a fault but is the one cause the app can do something about
 * before it becomes one.
 *
 * It acts once. A guard that keeps escalating after the session is already coming down would be
 * noise on top of the teardown.
 */
class GpuWatchdog(
    private val context: Context,
    private val budgetMs: Long,
    private val thermalCeilingC: Int,
    private val onAction: (Action, String) -> Unit,
) {
    enum class Action { WARN, ABORT }

    private val TAG = "GpuWatchdog"
    private val lock = Any()

    @Volatile
    private var running = false

    private var thread: Thread? = null

    /** Samples in a row with no frame: one is a warning, two is a stop. */
    private var stallSamples = 0

    /** Samples in a row with our processes wedged in uninterruptible sleep. */
    private var wedgedSamples = 0

    private var thermalTripped = false
    private var warned = false
    private var acted = false

    /** When the wedged-process check is allowed to fire: [GRACE_MS] after the session starts. */
    private var wedgedArmedAt = 0L

    private var lastSampleAt = 0L

    /** Whether the compositor has ever given us a frame interval at all, in this process. */
    private var frameTimingSeenHere = false

    /** The newest readings, for the HUD line and the log. */
    @Volatile
    var lastFrameIntervalMs: Long = -1
        private set

    @Volatile
    var lastWedged: Int = 0
        private set

    @Volatile
    var lastThermalC: Int = Int.MIN_VALUE
        private set

    private val power by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            // Arming is delayed, not gated on the first frame. The first version waited for
            // `firstFrameSeen`, which meant that for the first twenty-odd seconds of a session -
            // the part where Steam is warming up, and the part in which this device's fault has
            // actually happened every time - the watchdog was watching nothing.
            wedgedArmedAt = SystemClock.elapsedRealtime() + GRACE_MS
            acted = false
            warned = false
            stallSamples = 0
            wedgedSamples = 0
            thermalTripped = false
            lastSampleAt = 0L
        }
        thread = Thread({ runLoop() }, "gpu-watchdog").apply { isDaemon = true; start() }
    }

    /**
     * Whether the thread should keep going, read under the lock. It is a function and not a
     * `break` inside `synchronized { }` because this project's Kotlin is older than the
     * "break and continue in inline lambdas" language feature.
     */
    private fun isRunning(): Boolean = synchronized(lock) { running }

    private fun runLoop() {
        GpuGuardLog.log(TAG, "watchdog on: frame budget ${budgetMs}ms, thermal ceiling " +
            (if (thermalCeilingC > 0) "${thermalCeilingC}C" else "off"))
        while (isRunning()) {
            try {
                Thread.sleep(SAMPLE_MS)
            } catch (e: InterruptedException) {
                break
            }
            if (!isRunning()) break
            try {
                sample()
            } catch (t: Throwable) {
                // A watchdog that dies on an unexpected exception is worse than none: it
                // leaves the session running with nothing watching it, silently.
                GpuGuardLog.log(TAG, "sample failed: $t")
            }
        }
        GpuGuardLog.log(TAG, "watchdog off")
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        thread?.interrupt()
        thread = null
    }

    /** The session is already coming down: stop watching, and stop reporting. */
    fun standDown() {
        synchronized(lock) { running = false; acted = true }
        thread?.interrupt()
        thread = null
    }

    private fun sample() {
        val interactive = power?.isInteractive ?: true
        val presenting = SessionState.firstFrameSeen && !SessionState.suspended
        val wedgedArmed = !SessionState.suspended && SystemClock.elapsedRealtime() >= wedgedArmedAt

        // A screen that is off presents nothing and suspends the session's GPU work by design.
        // Treating that as a stall would stop every session the moment the phone sleeps.
        if (!interactive) {
            stallSamples = 0
            wedgedSamples = 0
            return
        }

        val frame = WaylandCompositor.recentFrameIntervalMs()
        lastFrameIntervalMs = frame
        if (frame >= 0 && !frameTimingSeenHere) {
            frameTimingSeenHere = true
            frameTimingSeen = true
        }
        // Deliberately not gated on `presenting`: a fence that will never be signalled parks its
        // waiters in D whether or not a frame was ever presented, and the clients that hit it are
        // the ones that never got that far.
        val wedged = if (wedgedArmed) uninterruptibleCount() else 0
        lastWedged = wedged
        val thermal = thermalCelsius()
        lastThermalC = thermal

        // A periodic reading, so a crash has a run-up recorded and not just a last word.
        val now = SystemClock.elapsedRealtime()
        if (lastSampleAt == 0L || now - lastSampleAt >= TRACE_MS) {
            lastSampleAt = now
            GpuGuardLog.log(TAG, "sample: frames " +
                (if (frame < 0) "unavailable" else "${frame}ms") +
                ", wedged $wedged${if (wedgedArmed) "" else " (check not armed yet)"}, " +
                (if (thermal == Int.MIN_VALUE) "thermal unknown" else "battery ${thermal}C"))
        }

        if (acted) return

        if (presenting && frame > budgetMs) {
            stallSamples++
            if (stallSamples == 1) {
                GpuGuardLog.log(TAG, "no frame for ${frame}ms (budget ${budgetMs}ms)")
                warn("no frame for ${frame / 1000}s")
            } else if (stallSamples >= 2) {
                act(Action.ABORT, "the session presented no frame for ${frame / 1000}s")
                return
            }
        } else {
            stallSamples = 0
        }

        if (wedgedArmed && wedged >= WEDGED_COUNT) {
            wedgedSamples++
            if (wedgedSamples == WEDGED_SAMPLES) {
                act(Action.ABORT, "$wedged processes of this app stuck in uninterruptible sleep " +
                    "for ${(WEDGED_SAMPLES * SAMPLE_MS) / 1000}s - the GPU has stopped completing work")
                return
            }
        } else {
            wedgedSamples = 0
        }

        if (thermalCeilingC > 0 && thermal != Int.MIN_VALUE && thermal >= thermalCeilingC) {
            if (thermalTripped) {
                act(Action.ABORT, "battery at ${thermal}C, at or above the ${thermalCeilingC}C ceiling")
                return
            }
            thermalTripped = true
            GpuGuardLog.log(TAG, "battery at ${thermal}C (ceiling ${thermalCeilingC}C)")
            warn("the device is at ${thermal}C")
        } else if (thermalCeilingC > 0 && thermal != Int.MIN_VALUE && thermal < thermalCeilingC - HYSTERESIS_C) {
            thermalTripped = false
        }
    }

    private fun warn(message: String) {
        if (warned) return
        warned = true
        onAction(Action.WARN, message)
    }

    private fun act(action: Action, message: String) {
        synchronized(lock) {
            if (acted) return
            acted = true
        }
        GpuGuardLog.log(TAG, "${action.name}: $message")
        onAction(action, message)
    }

    /**
     * How many processes of this app's own uid are in uninterruptible sleep right now. A GPU fence
     * that will never be signalled parks every thread waiting on it in `D`; ordinary I/O parks one
     * for a moment. Only processes of our own uid are counted, so a busy phone never trips this.
     */
    private fun uninterruptibleCount(): Int {
        val uid = android.os.Process.myUid()
        var count = 0
        val entries = File("/proc").listFiles() ?: return 0
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid <= 1) continue
            val stat = try {
                File(entry, "stat").readText()
            } catch (e: Exception) {
                continue
            }
            // "pid (comm) S ppid ...": comm may hold spaces and parentheses, the last ") " ends it.
            val after = stat.substringAfterLast(") ", "")
            if (after.isEmpty() || after[0] != 'D') continue
            if (uidOf(pid) == uid) count++
        }
        return count
    }

    private fun uidOf(pid: Int): Int = try {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }
                ?.substringAfter("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() ?: -1
        }
    } catch (e: Exception) {
        -1
    }

    /**
     * The battery's temperature in °C. It is the one thermal number an app may read on a stock
     * phone: the thermal zones under /sys/class/thermal are labelled for the platform, not for us,
     * and are refused on most devices. Tried first anyway, because where it is readable it is the
     * temperature that is actually rising.
     */
    private fun thermalCelsius(): Int {
        zoneCelsius()?.let { return it }
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (tenths == Int.MIN_VALUE) Int.MIN_VALUE else tenths / 10
        } catch (e: Exception) {
            Int.MIN_VALUE
        }
    }

    private fun zoneCelsius(): Int? {
        if (Build.VERSION.SDK_INT < 26) return null
        val zones = File("/sys/class/thermal").listFiles() ?: return null
        var hottest: Int? = null
        for (zone in zones) {
            val raw = try {
                File(zone, "temp").readText().trim().toLongOrNull()
            } catch (e: Exception) {
                null
            } ?: continue
            // Some kernels report millidegrees, some degrees; nothing a phone reaches is below 1 °C.
            val celsius = if (raw > 1000) (raw / 1000).toInt() else raw.toInt()
            if (celsius in 1..140 && (hottest == null || celsius > hottest)) hottest = celsius
        }
        return hottest
    }

    companion object {
        /** Two samples a second: fast enough to act inside a kernel's own fence timeout, cheap
         *  enough to run for hours (one /proc read per process per sample). */
        const val SAMPLE_MS = 500L

        /** A periodic line in the log, so a crash has a run-up and not just a last word. */
        const val TRACE_MS = 4000L

        /** Processes in uninterruptible sleep that count as "the GPU has stopped". */
        const val WEDGED_COUNT = 3

        /** Samples in a row that has to hold: ~1.5 s, so ordinary I/O never trips it. */
        const val WEDGED_SAMPLES = 3

        /** How far the temperature has to come back before the thermal trip is cleared. */
        const val HYSTERESIS_C = 3

        /**
         * How long after a session starts before a wedged process counts. Short enough to be
         * armed well before the window in which this device has faulted, long enough that the
         * startup's own I/O is over.
         */
        const val GRACE_MS = 8_000L

        /**
         * Whether the compositor has reported a frame interval at any point in this process.
         * Read by the guard's report: a stall detector with no frame timings is switched on and
         * blind at the same time, and the person reading the report has to be told which.
         */
        @Volatile
        var frameTimingSeen = false
    }
}
