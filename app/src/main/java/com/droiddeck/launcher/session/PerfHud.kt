package com.droiddeck.launcher.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
 * The lines in the top-right corner. They are few and short on purpose: this is read while a game
 * is being played, over a picture, and a HUD that covers the screen is a HUD that gets turned off.
 *
 * What it says is what decides whether a session lives. On a phone the frame rate is the number
 * that explains least - a session does not die short of frames, it dies short of memory - so the
 * memory line carries the threshold at which Android starts killing things to get some back, and
 * the cpu line carries what the device is doing at what clock, and how hot it is. The GPU figure
 * next to it is what the renderer is spending, which in software mode is cpu time, because there
 * is no GPU to ask: lavapipe *is* the cpu.
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
    @Volatile private var gpu = ""
    @Volatile private var gamePid = -1
    @Volatile private var gameName = ""
    private var driver: String? = null
    private var driverRead = false
    private var software = false

    private val listener = object : WaylandCompositor.GameListener {
        override fun onGameSurface(window: String?, gpuName: String?) {
            // gamescope forwards a fullscreen game's own buffers straight through and its composited
            // output when the Steam menu is up; each is a different window here. Compositor thread.
            if (!gpuName.isNullOrEmpty() && gpuName != gpu) {
                gpu = gpuName
                // Said once, not on the screen: what draws and with which driver is worth knowing
                // but not worth four lines of a HUD that has to stay out of the way.
                val label = driverLabel()
                Log.i(TAG, if (label == null) gpuName else context.getString(R.string.hud_gpu_driver, gpuName, label))
            }
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
            Log.i(TAG, "game program: ${program.ifBlank { "?" }} [$pid]")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTick).coerceAtLeast(1L) / 1000f
            lastTick = now
            val rate = frames.getAndSet(0) / dt
            // Sampled once and shared: both percentages are differences between two readings, so
            // neither can be asked for twice in a tick, and the second is measured against the
            // first's interval.
            val cpu = stats.cpuPercent()
            text = lines(rate, cpu, stats.processCpuPercent(gamePid), stats.memory())
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
        driverLabel() // read once: what draws decides how the GPU figure below is measured
        WaylandCompositor.setGameListener(listener)
        handler.post(tick)
    }

    fun stop() {
        running = false
        WaylandCompositor.clearGameListener(listener)
        text = ""
    }

    private fun lines(base: Float, cpu: Int, gameCpu: Int, memory: PerfStats.Memory?): String {
        val out = ArrayList<String>(4)
        out += head(base)
        cpuLine(cpu, gpuPercent(gameCpu))?.let { out += it }
        memory?.let { out += ramLine(it) }
        tailLine()?.let { out += it }
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
     * The two figures that say whether the device has anything left to give, and the clock it is
     * giving it at. Thermal only appears once the device is actually throttling - below that it is
     * noise on a line that has to be readable at a glance.
     */
    private fun cpuLine(cpu: Int, gpu: Int): String? {
        if (cpu < 0 && gpu < 0) return null
        return buildString {
            if (cpu >= 0) append(context.getString(R.string.hud_cpu, cpu))
            if (gpu >= 0) {
                if (isNotEmpty()) append(" · ")
                append(context.getString(R.string.hud_gpu, gpu))
            }
            val mhz = stats.cpuMHz()
            if (mhz > 0) append(" · ").append(String.format(Locale.US, "%.2f GHz", mhz / 1000.0))
            thermalLabel(stats.thermal())?.let { append(" · ").append(it) }
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

    /** What the program on screen is holding, beside the session it is drawing at. */
    private fun tailLine(): String? {
        val display = outputSize?.let {
            context.getString(R.string.hud_display, it.first, it.second, SessionState.refreshHz) +
                if (SessionState.fpsLimit > 0) context.getString(R.string.hud_display_cap, SessionState.fpsLimit) else ""
        }
        val rss = stats.rssMb(gamePid)
        return when {
            rss > 0 && display != null -> context.getString(R.string.hud_game_rss, size(rss)) + " · " + display
            rss > 0 -> context.getString(R.string.hud_game_rss, size(rss))
            else -> display
        }
    }

    /**
     * What share of the device the renderer is using. Asked of the driver's own counter when the
     * device publishes one; failing that, on software rendering, the game's own process is the
     * renderer - FEX, DXVK and lavapipe all live in it - so the share of the cpu it takes *is* the
     * GPU figure. Where neither can be said, nothing is shown: a GPU that cannot be asked is not a
     * GPU at 0%.
     */
    private fun gpuPercent(gameCpu: Int): Int {
        val direct = stats.gpuPercent()
        if (direct >= 0) return direct
        return if (software) gameCpu else -1
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
    private fun driverLabel(): String? {
        if (driverRead) return driver
        driverRead = true
        val id = SessionPrefs.linuxDriver(context)
        val manager = try { LinuxVulkanDriverManager(context) } catch (t: Throwable) { null }
        software = manager?.isSoftwareDriver(id) ?: false
        val value = if (id.isEmpty() || manager == null) null else runCatching {
            "${manager.getDriverName(id)} ${manager.getDriverVersion(id)}".trim().ifEmpty { null }
        }.getOrNull()
        driver = value
        return value
    }

    private fun fps(value: Float) = String.format(Locale.US, "%.0f fps", value)

    /** MiB as GB once it is worth saying in GB, so a line stays short enough to read at a glance. */
    private fun size(mb: Long): String =
        if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0) else context.getString(R.string.hud_mb, mb)

    private companion object {
        const val TAG = "PerfHud"
    }
}
