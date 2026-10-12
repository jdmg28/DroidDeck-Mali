package com.droiddeck.launcher.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The top-right line, and now the lines under it: the frame rate is the number everyone looks at,
 * and on a phone it is the one that explains the least. A session that dies is not short of frames,
 * it is short of memory; a session that slows down is usually hot. So the HUD says what the device
 * has left - including how close Android is to killing something for it - what the cpu is doing
 * and at what clock, what is drawing and with which driver, and how much memory the program whose
 * frames are on screen is holding, which is the number that has decided every session so far.
 *
 * The base rate is the game window's own frames, counted here from the compositor's per-frame
 * callback with or without an engine - what Steam's overlay counts too. The presented rate comes
 * from the engine's telemetry while it generates.
 */
class PerfHud(context: Context, private val outputSize: Pair<Int, Int>? = null) {
    /** Empty when the HUD is off or not yet started. One line per thing measured. */
    var text by mutableStateOf("")
        private set

    private val context = context.applicationContext
    private val stats = PerfStats(context)
    private val frames = AtomicInteger()
    private var lastTick = 0L
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    /** The session's own switch (drawer), with the Downloads file as a device-side override. */
    private val enabled: Boolean
        get() = SessionPrefs.hudEnabled(context)
            && !File(android.os.Environment.getExternalStorageDirectory(), "Download/droiddeck-no-hud").exists()

    /** Told, on the main thread, when the window presenting frames changes. */
    var onPresentingWindowChanged: (() -> Unit)? = null
    private var lastWindow: String? = null

    /**
     * Whose frames are on screen, when the compositor says. Kept here as well as signalled: a
     * session whose picture has stopped is a different problem depending on whether the window
     * still presenting is the game's or Steam's, and that is the one thing about it the session log
     * has never been able to say.
     */
    @Volatile var presentingWindow: String? = null
        private set

    /** What the compositor says it draws with, and the program the frames come from. */
    private var gpu = ""
    @Volatile private var gamePid = -1
    @Volatile private var gameName = ""
    private var driver: String? = null

    private val listener = object : WaylandCompositor.GameListener {
        override fun onGameSurface(window: String?, gpuName: String?) {
            // gamescope forwards a fullscreen game's own buffers straight through and its composited
            // output when the Steam menu is up; each is a different window here. Compositor thread.
            if (!gpuName.isNullOrEmpty()) gpu = gpuName
            presentingWindow = window
            if (window != null && window != lastWindow) {
                lastWindow = window
                handler.post { onPresentingWindowChanged?.invoke() }
            }
        }
        override fun onGameFrame() { frames.incrementAndGet() }
        override fun onGameProgram(pid: Int, program: String) {
            gamePid = pid
            gameName = program
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTick).coerceAtLeast(1L) / 1000f
            lastTick = now
            val rate = frames.getAndSet(0) / dt
            // Sampled once and used for the line below: the cpu figure is a difference between two
            // readings, so it must not be asked for twice in a tick.
            text = lines(rate, stats.cpuPercent(), stats.memory())
            handler.postDelayed(this, 1000)
        }
    }

    /** Re-reads the switch: starts or stops to match it. */
    fun refresh() {
        if (enabled) start() else stop()
    }

    fun start() {
        if (!enabled || running) return
        running = true
        lastTick = SystemClock.elapsedRealtime()
        frames.set(0)
        stats.cpuPercent() // the first reading is the one the second is measured against
        WaylandCompositor.setGameListener(listener)
        handler.post(tick)
    }

    fun stop() {
        running = false
        WaylandCompositor.clearGameListener(listener)
        text = ""
    }

    private fun lines(base: Float, cpu: Int, memory: PerfStats.Memory?): String {
        val out = ArrayList<String>(6)
        out += head(base)
        memory?.let { out += ramLine(it) }
        gameLine()?.let { out += it }
        cpuLine(cpu)?.let { out += it }
        gpuLine()?.let { out += it }
        displayLine()?.let { out += it }
        return out.joinToString("\n")
    }

    /** The frame rate, and how long a frame is taking: 30 fps says less than "30 fps · 33 ms". */
    private fun head(base: Float): String {
        val smoothed = WaylandCompositor.recentFrameIntervalMs()
        val ms = when {
            smoothed > 0 -> smoothed
            base > 0f -> (1000f / base).toLong()
            else -> 0L
        }
        return if (ms > 0) context.getString(R.string.hud_rate, line(base), ms) else line(base)
    }

    private fun line(base: Float): String {
        val mode = FrameGen.mode(context)
        if (mode.engine == FrameGen.ENGINE_OFF) return fps(base)
        val lsfg = mode.engine == FrameGen.ENGINE_LSFG
        val name = if (lsfg) "LSFG" else "Win-FG"
        val label = FrameGen.label(context, mode)
        if (lsfg && Lossless.cacheFile(context)?.isFile != true) return context.getString(R.string.hud_fg_needs_lossless, name)
        val fg = try { WaylandCompositor.nativeFrameGenStats() } catch (t: Throwable) { null }
        val problem = try { WaylandCompositor.nativeFrameGenProblem() } catch (t: Throwable) { -1 }
        return when {
            problem == 1 -> context.getString(R.string.hud_fg_unsupported, name)
            problem == 2 -> context.getString(R.string.hud_fg_failed, name)
            fg != null && fg[3] > 1f -> String.format(Locale.US, "%s  %.0f → %.0f fps", label, base, fg[3])
            else -> context.getString(R.string.hud_fg_starting, label, fps(base))
        }
    }

    /**
     * Memory, with the number that matters most on a phone: Android's own threshold, the point
     * past which it starts killing processes to get memory back. Free memory above that threshold
     * is a session that survives; below it, one that is being hunted.
     */
    private fun ramLine(memory: PerfStats.Memory): String =
        context.getString(R.string.hud_ram, size(memory.availMb), size(memory.totalMb), memory.thresholdMb) +
            if (memory.low) " · " + context.getString(R.string.hud_low_memory) else ""

    /**
     * The program whose frames are on screen and what it is holding. This is the number that has
     * ended every session examined so far: a title sits at 940 MB and then takes another 1.5 GB
     * in twenty seconds, and the session dies at four hundred MB left.
     */
    private fun gameLine(): String? {
        val pid = gamePid
        if (pid <= 0) return null
        val name = gameName.ifBlank { context.getString(R.string.hud_game) }
        val rss = stats.rssMb(pid)
        return if (rss > 0) context.getString(R.string.hud_game_rss, name, pid, size(rss))
        else context.getString(R.string.hud_game_pid, name, pid)
    }

    private fun cpuLine(percent: Int): String? {
        if (percent < 0) return null
        return buildString {
            append(context.getString(R.string.hud_cpu, percent))
            val mhz = stats.cpuMHz()
            if (mhz > 0) append(" · ").append(String.format(Locale.US, "%.2f GHz", mhz / 1000.0))
            append(" · ").append(context.getString(R.string.hud_cores, stats.coreCount))
            thermalLabel(stats.thermal())?.let { append(" · ").append(it) }
        }
    }

    private fun gpuLine(): String? {
        val name = gpu
        if (name.isBlank()) return null
        val with = driver()
        return if (with.isNullOrBlank()) context.getString(R.string.hud_gpu, name)
        else context.getString(R.string.hud_gpu_driver, name, with)
    }

    private fun displayLine(): String? {
        val size = outputSize ?: return null
        return context.getString(R.string.hud_display, size.first, size.second, SessionState.refreshHz) +
            if (SessionState.fpsLimit > 0) context.getString(R.string.hud_display_cap, SessionState.fpsLimit) else ""
    }

    /**
     * Only said once it is worth saying: below "moderate" the device is not throttling yet, so
     * nothing is printed there. The statuses' own numbers (PowerManager.THERMAL_STATUS_MODERATE
     * onwards), written out because they are an Android 11 constant and this app builds for 26 -
     * PerfStats returns -1 on anything older, so the rest never runs there.
     */
    private fun thermalLabel(status: Int): String? = when (status) {
        2 -> context.getString(R.string.hud_thermal_moderate)
        3 -> context.getString(R.string.hud_thermal_severe)
        4 -> context.getString(R.string.hud_thermal_critical)
        5 -> context.getString(R.string.hud_thermal_emergency)
        6 -> context.getString(R.string.hud_thermal_shutdown)
        else -> null
    }

    /** The Linux driver the session draws with, read once: it cannot change under a running one. */
    private fun driver(): String? {
        driver?.let { return it.ifEmpty { null } }
        val id = SessionPrefs.linuxDriver(context)
        val value = if (id.isEmpty()) null else runCatching {
            val manager = LinuxVulkanDriverManager(context)
            "${manager.getDriverName(id)} ${manager.getDriverVersion(id)}".trim().ifEmpty { null }
        }.getOrNull()
        driver = value.orEmpty()
        return value
    }

    private fun fps(value: Float) = String.format(Locale.US, "%.0f fps", value)

    /** MiB as GB once it is worth saying in GB, so a line stays short enough to read at a glance. */
    private fun size(mb: Long): String =
        if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0) else context.getString(R.string.hud_mb, mb)
}
