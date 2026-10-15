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
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
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

    /**
     * The clean layout the fork's users asked for - one figure per line, a blank line between:
     * Ram, Cpu, Gpu, temperature, the device's own name, and the frame rate. Everything else the
     * HUD used to say (frame time, clocks, thresholds, the game's RSS, the display mode, frame
     * generation) moved to the session log, where a diagnosis needs it; a HUD read over a game
     * carries the six numbers a player acts on.
     */
    private fun lines(base: Float, cpu: Int, gameCpu: Int, memory: PerfStats.Memory?): String {
        val out = ArrayList<String>(6)
        memory?.let { out += context.getString(R.string.hud_ram_pct, usedPercent(it)) }
        if (cpu >= 0) out += context.getString(R.string.hud_cpu_pct, cpu)
        gpuPercent(gameCpu).takeIf { it >= 0 }?.let { out += context.getString(R.string.hud_gpu_pct, it) }
        stats.celsius().takeIf { it >= 0 }?.let { out += context.getString(R.string.hud_temp, it) }
        out += context.getString(R.string.hud_device, deviceName())
        out += context.getString(R.string.hud_fps, base.toInt().coerceAtLeast(0))
        return out.joinToString("\n\n")
    }

    /** How full the device's memory is, as one percentage: what a phone has left to give. */
    private fun usedPercent(memory: PerfStats.Memory): Int {
        if (memory.totalMb <= 0) return 0
        val used = (memory.totalMb - memory.availMb).coerceAtLeast(0)
        return (used * 100 / memory.totalMb).toInt().coerceIn(0, 100)
    }

    /** The SoC as the device names it ("Mediatek Dimensity 8300-Ultra"), or its hardware string. */
    private fun deviceName(): String {
        val manufacturer = if (android.os.Build.VERSION.SDK_INT >= 31)
            android.os.Build.SOC_MANUFACTURER.replaceFirstChar { it.uppercase() } else ""
        val model = if (android.os.Build.VERSION.SDK_INT >= 31)
            android.os.Build.SOC_MODEL.takeIf { it.isNotBlank() && it != android.os.Build.UNKNOWN } else null
        return when {
            model != null && manufacturer.isNotEmpty() -> "$manufacturer $model"
            model != null -> model
            else -> android.os.Build.HARDWARE.ifBlank { "?" }
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

    private companion object {
        const val TAG = "PerfHud"
    }
}
