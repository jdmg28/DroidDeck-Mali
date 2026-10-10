/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu.guard

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The GPU safety guard.
 *
 * What this is for, stated plainly: on this device a Steam session ended in a kernel panic inside
 * the Mali kernel module (`CS_FATAL ... CS_BUS_FAULT` -> `FENCE_SIGNAL timeout(3000 ms)` ->
 * `Unexpected kernel BRK exception at EL1` in `kbasep_csf_sync_kcpu_dump_locked`). An APK cannot
 * repair a kernel module, cannot disable a watchdog it does not own, and cannot lower SELinux. It
 * also cannot run at all in the seconds where that happens - by the time Android is back up, the
 * app is starting from nothing.
 *
 * So the guard does the four things an app CAN do:
 *
 * 1. **Break the loop.** The next start reads Android's own record of why the phone booted. A boot
 *    recorded as a crash leaves the latch locked: the launch is refused until the user says they
 *    know and want to try. A device that panics again immediately after every boot is the one
 *    genuinely dangerous outcome - this is the part that prevents it, and it is the part that
 *    matters most.
 * 2. **Reduce the load before it is applied.** [SafeProfile] caps the output and the frame rate and
 *    turns off HDR, frame generation, the upscaler, the realtime queues and the shader cache, so
 *    the experimental PanVK path is asked for less of what it has never been validated on.
 * 3. **Test the GPU before Steam does.** A bounded, logged, tiny workload inside the runtime, with
 *    a wall-clock timeout the app enforces. If the GPU cannot do that much, Steam never starts -
 *    and the failure is a line in a log instead of a reboot.
 * 4. **Take the session down first.** [GpuWatchdog] watches for a session that has stopped
 *    presenting, or processes wedged on a fence, or a device that is too hot, and stops the
 *    session itself rather than leaving the kernel to discover the same thing three seconds later
 *    on a path that ends in a panic.
 *
 * None of it makes the panic impossible. It makes it far less likely to be repeated, and it makes
 * every attempt leave evidence behind.
 */
object GpuGuard {

    /** Where the guard stands on this start. */
    enum class Level {
        /** Nothing has crashed; the chosen profile runs, if any. */
        NORMAL,

        /** A crash is on record. Launching is allowed, at [SafeProfile.SAFE] or stricter. */
        GUARDED,

        /** A crash is on record and nobody has acknowledged it. Launching is refused. */
        LOCKED,
    }

    /** What a request to start a session is answered with. */
    class Decision(
        val allowed: Boolean,
        val level: Level,
        val profile: SafeProfile,
    ) {
        val blocked: Boolean get() = !allowed
    }

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile
    var level: Level = Level.NORMAL
        private set

    /** The profile in force this start: the chosen one, raised by the latch. */
    @Volatile
    var profile: SafeProfile = SafeProfile.OFF
        private set

    /** Why [level] is what it is, for the page and the device report. */
    @Volatile
    var levelReason: String = ""
        private set

    /**
     * A notice to show over a running session ("the GPU has stalled"); cleared when it ends. The
     * state object is itself the thread-safe part - it is written from the watchdog's thread and
     * read from the UI's - so no @Volatile here (it cannot be applied to a delegated property).
     */
    var alert by mutableStateOf<String?>(null)
        internal set

    /** The guard ended this session itself, so the notice over it says stopped, not stalled. */
    var sessionStopped by mutableStateOf(false)
        private set

    /** The last pre-flight probe's verdict, for the settings page. */
    @Volatile
    var lastProbe: Probe.Result? = null
        private set

    private var watchdog: GpuWatchdog? = null

    private val lock = Any()

    // ── Start of the app process ──────────────────────────────────────────────────────────────

    /**
     * Called once from the Application. Accounts for the boot the app has just started on, once per
     * boot and not once per process start: `elapsedRealtime()` only ever rises within a boot, so an
     * uptime behind the one on file means this is a new boot, and only then is the boot reason read
     * and counted. Without that, a phone that crashed would count the same crash again every time
     * the app was opened, and the latch could never relax.
     */
    fun init(context: Context) {
        GpuGuardLog.bind(context)
        val app = context.applicationContext
        val uptime = SystemClock.elapsedRealtime()
        val seen = GpuSafetyPrefs.lastSeenUptime(app)
        val newBoot = seen < 0L || uptime < seen
        GpuSafetyPrefs.setLastSeenUptime(app, uptime)
        if (!newBoot) {
            refresh(app)
            GpuGuardLog.log("guard", "app started (same boot, ${formatUptime(uptime)} up); " +
                "level $level, profile ${profile.id}${if (levelReason.isEmpty()) "" else ", $levelReason"}")
            return
        }

        val reading = BootReason.read()
        GpuGuardLog.log("boot", "app started on a new boot (${formatUptime(uptime)} up); " +
            "boot reason: ${if (reading.raw.isEmpty()) "not readable" else reading.raw} [${reading.kind}]")
        if (reading.fatal) {
            val count = GpuSafetyPrefs.fatalBootCount(app) + 1
            GpuSafetyPrefs.setFatalBootCount(app, count)
            GpuSafetyPrefs.setLastFatalBootAt(app, System.currentTimeMillis())
            GpuSafetyPrefs.setLastFatalBootReason(app, reading.toString())
            GpuSafetyPrefs.setCleanSessionsSinceFatal(app, 0)
            if (GpuSafetyPrefs.lockAfterFatalBoot(app)) {
                GpuSafetyPrefs.setLockEngaged(app, true)
                GpuGuardLog.log("latch", "boot recorded as ${reading.kind}: launch locked until it is " +
                    "re-armed (crash $count on record)")
            } else {
                GpuGuardLog.log("latch", "boot recorded as ${reading.kind}: crash $count on record; " +
                    "locking is off, so the launch is only restricted")
            }
        } else if (reading.kind == BootReason.Kind.UNKNOWN) {
            GpuGuardLog.log("boot", "the boot reason could not be read; the guard cannot tell whether " +
                "the last power-off was clean and leaves the latch as it is")
        }
        refresh(app)
    }

    /** Recomputes [level] and [profile] from the settings and the crash history. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val fatal = GpuSafetyPrefs.fatalBootCount(app)
        val locked = GpuSafetyPrefs.lockEngaged(app)
        val chosen = SafeProfile.of(GpuSafetyPrefs.chosenProfile(app))
        synchronized(lock) {
            if (!GpuSafetyPrefs.guardEnabled(app)) {
                level = Level.NORMAL
                profile = SafeProfile.OFF
                levelReason = "off"
                return
            }
            when {
                locked -> {
                    level = Level.LOCKED
                    profile = strictest(chosen, SafeProfile.STRICT)
                    levelReason = GpuSafetyPrefs.lastFatalBootReason(app)
                }
                fatal > 0 -> {
                    level = Level.GUARDED
                    profile = strictest(chosen, SafeProfile.SAFE)
                    levelReason = GpuSafetyPrefs.lastFatalBootReason(app)
                }
                else -> {
                    level = Level.NORMAL
                    profile = chosen
                    levelReason = ""
                }
            }
        }
    }

    private fun strictest(a: SafeProfile, b: SafeProfile): SafeProfile =
        if (a.ordinal >= b.ordinal) a else b

    // ── Deciding a launch ─────────────────────────────────────────────────────────────────────

    /**
     * Whether a session may start, and under what profile. The lock is the only thing that refuses;
     * [GUARDED] allows the launch because a device with one crash on record still has to be usable,
     * and the answer to "it crashed once" is a restricted session, not a dead app.
     */
    fun decision(context: Context): Decision {
        refresh(context)
        val allowed = level != Level.LOCKED
        GpuGuardLog.log("guard", "launch ${if (allowed) "allowed" else "refused"}: level $level, " +
            "profile ${profile.id}")
        return Decision(allowed, level, profile)
    }

    /** Whether the app may start a session by itself (Setup's "open straight into Steam"). */
    fun blocksAutoStart(context: Context): Boolean {
        refresh(context)
        return level == Level.LOCKED
    }

    /**
     * The user has read what happened and chosen to go ahead: the lock is lifted for as long as the
     * device keeps behaving. Nothing is forgotten - the crash count stays, and the profile stays at
     * [SafeProfile.SAFE] or stricter until three sessions in a row end cleanly.
     */
    fun rearm(context: Context) {
        val app = context.applicationContext
        GpuSafetyPrefs.setLockEngaged(app, false)
        GpuSafetyPrefs.setCleanSessionsSinceFatal(app, 0)
        GpuGuardLog.log("latch", "re-armed by the user: the lock is lifted, profile ${profile.id} stays " +
            "until ${GpuSafetyPrefs.CLEAN_RUNS_TO_RELAX} sessions end cleanly")
        refresh(app)
    }

    /** The latch's memory cleared: for someone who wants to start over, not for routine use. */
    fun forget(context: Context) {
        val app = context.applicationContext
        GpuSafetyPrefs.reset(app)
        GpuGuardLog.log("latch", "crash history cleared")
        refresh(app)
    }

    // ── A session's lifetime ──────────────────────────────────────────────────────────────────

    /** The session is starting: cap the output and the frame rate, and start watching. */
    fun beginSession(context: Context, sessionDir: java.io.File?) {
        val app = context.applicationContext
        refresh(app)
        alert = null
        sessionStopped = false
        constrainLaunch()
        GpuGuardLog.openSession(sessionDir, listOf(
            "level $level, profile ${profile.id}",
            "output ${SessionState.outputSize.first}x${SessionState.outputSize.second}" +
                (if (SessionState.followScreen) " (following the screen)" else ""),
            "frame cap ${SessionState.fpsLimit}, hdr ${if (SessionState.hdr) "on" else "off"}",
        ))
    }

    /**
     * Brings the session's own launch parameters down to what the profile allows, in place. Both
     * halves of a session read [SessionState], the activity for the compositor and the service for
     * gamescope's command line, and the service runs second, so this is where the numbers are
     * finally decided.
     */
    fun constrainLaunch() {
        val p = profile
        if (p == SafeProfile.OFF) return
        val size = SessionState.outputSize
        val capped = p.constrained(size, SessionState.followScreen)
        if (capped != size) {
            SessionState.outputSize = capped
            GpuGuardLog.log("guard", "output capped: ${size.first}x${size.second} -> " +
                "${capped.first}x${capped.second} (${p.maxPixels} px, profile ${p.id})")
        }
        val fps = p.clampedFps(SessionState.fpsLimit)
        if (fps != SessionState.fpsLimit) {
            GpuGuardLog.log("guard", "frame cap: ${SessionState.fpsLimit} -> $fps (profile ${p.id})")
            SessionState.fpsLimit = fps
        }
        if (SessionState.hdr && !p.allowsHdr) {
            SessionState.hdr = false
            GpuGuardLog.log("guard", "HDR off (profile ${p.id})")
        }
        if (!p.allowsSecondScreen && SessionState.secondScreenMode !=
            com.droiddeck.launcher.input.SecondScreenMode.NONE
        ) {
            SessionState.secondScreenMode = com.droiddeck.launcher.input.SecondScreenMode.NONE
            GpuGuardLog.log("guard", "second screen off (profile ${p.id})")
        }
    }

    /** The watchdog, when the profile or the settings ask for one. Null when nothing should watch. */
    fun startWatchdog(context: Context, onAction: (GpuWatchdog.Action, String) -> Unit): GpuWatchdog? {
        val app = context.applicationContext
        if (!GpuSafetyPrefs.guardEnabled(app) || !GpuSafetyPrefs.liveWatchdog(app)) return null
        val dog = GpuWatchdog(
            context = app,
            budgetMs = GpuSafetyPrefs.stallBudgetMs(app).toLong(),
            thermalCeilingC = GpuSafetyPrefs.thermalCeilingC(app),
            onAction = onAction,
        )
        synchronized(this) { watchdog = dog }
        dog.start()
        return dog
    }

    fun stopWatchdog() {
        val dog = synchronized(this) { watchdog.also { watchdog = null } }
        dog?.standDown()
    }

    /** The watchdog said something; the session decides what to do about it. */
    fun raiseAlert(message: String) {
        alert = message
    }

    fun clearAlert() {
        alert = null
    }

    /** The session ended on its own: evidence that the device is behaving, one run at a time. */
    fun noteSessionEnded(context: Context, endedNormally: Boolean) {
        val app = context.applicationContext
        stopWatchdog()
        GpuGuardLog.closeSession(if (endedNormally) "clean" else "abnormal")
        // An ending the guard forced keeps its notice on screen: the user has to be told why the
        // session they were playing is gone. It is cleared when the next one starts.
        if (!endedNormally) return
        alert = null
        val clean = GpuSafetyPrefs.cleanSessionsSinceFatal(app) + 1
        GpuSafetyPrefs.setCleanSessionsSinceFatal(app, clean)
        GpuGuardLog.log("latch", "clean session $clean of ${GpuSafetyPrefs.CLEAN_RUNS_TO_RELAX}")
        if (clean >= GpuSafetyPrefs.CLEAN_RUNS_TO_RELAX && GpuSafetyPrefs.lockEngaged(app)) {
            GpuSafetyPrefs.setLockEngaged(app, false)
            GpuGuardLog.log("latch", "${GpuSafetyPrefs.CLEAN_RUNS_TO_RELAX} clean sessions in a row: " +
                "the lock is lifted on its own")
        }
        refresh(app)
    }

    /** The guard stopped a session itself. That is evidence the other way, and it resets the count. */
    fun noteGuardStop(context: Context, reason: String) {
        val app = context.applicationContext
        GpuSafetyPrefs.countGuardStop(app)
        GpuSafetyPrefs.setCleanSessionsSinceFatal(app, 0)
        alert = reason
        sessionStopped = true
        GpuGuardLog.log("guard", "session stopped by the guard: $reason")
    }

    /** The pre-flight probe's verdict, remembered for the page and the log. */
    fun noteProbe(context: Context, result: Probe.Result) {
        lastProbe = result
        GpuSafetyPrefs.setLastProbe(context.applicationContext, result.ok, result.summary)
        GpuGuardLog.log("probe", result.summary)
    }

    // ── Reporting ─────────────────────────────────────────────────────────────────────────────

    /** English, for the device report and a bug report someone else has to read. */
    fun report(context: Context): String {
        val app = context.applicationContext
        val p = GpuSafetyPrefs
        val lines = mutableListOf<String>()
        lines.add("GPU guard: ${if (p.guardEnabled(app)) "on" else "off"}, level $level, profile ${profile.id}")
        lines.add("  boot reason (this boot): ${BootReason.read()}")
        lines.add("  boots recorded as a crash: ${p.fatalBootCount(app)}")
        val at = p.lastFatalBootAt(app)
        if (at > 0L) lines.add("  last crash: ${stamp.format(Date(at))} (${p.lastFatalBootReason(app)})")
        lines.add("  launch locked: ${p.lockEngaged(app)}")
        lines.add("  clean sessions since the last crash: ${p.cleanSessionsSinceFatal(app)}")
        lines.add("  sessions stopped by the guard: ${p.guardStops(app)}")
        lines.add("  watchdog: ${p.liveWatchdog(app)} (frame budget ${p.stallBudgetMs(app)} ms, " +
            "thermal ceiling ${if (p.thermalCeilingC(app) > 0) "${p.thermalCeilingC(app)} C" else "off"})")
        lines.add("  pre-flight probe: ${p.preflightProbe(app)}")
        val probeAt = p.lastProbeAt(app)
        if (probeAt > 0L) lines.add("  last probe: ${stamp.format(Date(probeAt))} - " +
            "${if (p.lastProbeOk(app)) "passed" else "FAILED"}: ${p.lastProbeSummary(app)}")
        return lines.joinToString("\n")
    }

    private fun formatUptime(millis: Long): String {
        val seconds = millis / 1000
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h${m}m" else "${m}m"
    }
}
