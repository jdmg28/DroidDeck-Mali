/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu.guard

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The pre-flight GPU probe: one small, logged, time-boxed piece of work pushed through the driver
 * before gamescope and the Steam client are started.
 *
 * The point is not to prove the GPU works - `vulkaninfo` already says that much - it is to refuse
 * to hand the experimental PanVK path a 700 MB client before it has finished one 4 KB fill. The
 * fault that took this device down was a fence that was never signalled; a fence that is never
 * signalled for a 4 KB buffer is the same failure, found in two seconds, with the phone still up.
 *
 * It is a proot invocation like any other, and the app - not the guest - owns the clock: the guest
 * script has its own `timeout`s, and if the process is still alive when the budget runs out the app
 * asks proot to wind its tree down and then kills it. A probe that hangs is itself an answer, and
 * it is recorded as one.
 */
object Probe {
    /** The script inside the runtime, staged fresh from the apk at every session start. */
    private const val SCRIPT = "/usr/local/bin/droiddeck-gpu-probe"

    /** Its exit code for "the GPU did not finish what was queued". */
    private const val EXIT_FAILED = 78

    /** `timeout`'s, and a SIGKILL's: the guest's own clock ran out. */
    private const val EXIT_TIMEOUT = 124
    private const val EXIT_KILLED = 137

    class Result(
        /** Whether a session may start. */
        val ok: Boolean,
        /** One line for the log, the page and the device report. */
        val summary: String,
        /** Everything the guest said, for the session's folder. */
        val lines: List<String>,
        /** The verdict, in the script's own words, when there is one. */
        val verdict: String?,
    ) {
        /** The GPU was asked and did not answer - the one result that is evidence, not absence of it. */
        val stalled: Boolean
            get() = !ok && verdict != null && verdict.startsWith("DD_GPU_PROBE_SUBMIT_TIMEOUT")

        val skipped: Boolean
            get() = verdict != null && verdict.startsWith("DD_GPU_PROBE_SKIPPED")

        companion object {
            fun of(exit: Int, lines: List<String>): Result {
                val verdict: String? = lines.lastOrNull { it.startsWith("DD_GPU_PROBE_") }
                if (verdict == null) {
                    return Result(false, "the probe produced no verdict (exit $exit)", lines, null)
                }
                val body = verdict.substringAfter(':').trim().ifEmpty { verdict }
                val ok = verdict.startsWith("DD_GPU_PROBE_OK") || verdict.startsWith("DD_GPU_PROBE_SKIPPED")
                return Result(ok, body, lines, verdict)
            }
        }
    }

    /**
     * Runs the probe and waits. [timeoutMs] is a wall clock on the whole thing, including proot's
     * own start; the guest's internal budgets are shorter, so a normal run ends well inside it.
     *
     * Must not be called on the main thread: it waits for a process.
     */
    /**
     * A file that turns the probe off without the app: `droiddeck-no-gpu-probe` in the app's own
     * external files directory, the place the session's other switch file lives. Every other
     * escape from this guard is a switch inside the app, and a probe that has gone wrong is
     * exactly the case where someone may not be able to get to them.
     */
    private fun skipFile(context: Context): File? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            context.getExternalFilesDir(null)?.let { File(it, "droiddeck-no-gpu-probe") }
        else null

    fun run(context: Context, profile: SafeProfile, timeoutMs: Long = 60_000L): Result {
        val app = context.applicationContext
        skipFile(app)?.takeIf { it.isFile }?.let {
            val result = Result(true, "the probe is switched off by ${it.name}",
                listOf("DD_GPU_PROBE_SKIPPED: ${it.name} is present"), "DD_GPU_PROBE_SKIPPED: switched off")
            GpuGuard.noteProbe(app, result)
            GpuGuardLog.log("probe", "skipped: ${it.name} is present")
            return result
        }
        val root = LinuxRuntime.rootDir(app)
        val script = File(root, SCRIPT.substring(1))
        if (!script.isFile) {
            return Result(true, "the probe is not in this runtime; skipped", listOf(
                "DD_GPU_PROBE_SKIPPED: $SCRIPT is not present in the runtime (staged at session start)"),
                "DD_GPU_PROBE_SKIPPED: script missing").also { GpuGuard.noteProbe(app, it) }
        }

        val runtimeDir = File(app.filesDir, ".wayland-rt").apply { mkdirs() }
        val guest = ArrayList<String>()
        guest.add("/usr/bin/env")
        guest.add("-i")
        guest.add("HOME=/root")
        guest.add("USER=root")
        guest.add("PATH=/usr/local/bin:/usr/bin:/bin")
        guest.add("TERM=xterm-256color")
        guest.add("LANG=C.UTF-8")
        guest.add("TZ=" + java.util.TimeZone.getDefault().id)
        guest.add("XDG_RUNTIME_DIR=" + LinuxRuntime.GUEST_RUNTIME_DIR)
        // The driver the session would draw with, so the probe is not testing a different stack.
        // Named twice on purpose: BL_VK_DRIVER is what the session script reads (and what the
        // probe's python stage dlopens), VK_DRIVER_FILES is what the Vulkan loader `vulkaninfo`
        // goes through reads. Setting only the first would leave enumeration pointed at the
        // runtime's own driver, and the probe would fail over a name it never asked about.
        LinuxVulkanDriver.resolveIcdPath(app, SessionPrefs.linuxDriver(app))?.let { icd ->
            guest.add(LinuxVulkanDriver.ENV + "=" + icd)
            guest.add("VK_DRIVER_FILES=" + icd)
        }
        if (guest.none { it.startsWith(LinuxVulkanDriver.ENV + "=") }) {
            LinuxRuntime.vulkanIcd(app)?.let {
                guest.add("VK_ICD_FILENAMES=" + it.path)
                guest.add("VK_DRIVER_FILES=" + it.path)
            }
        }
        guest.add("PANVK_KBASE_DRI3=0")
        guest.add("MESA_LOADER_DRIVER_OVERRIDE=zink")
        guest.add("GALLIUM_DRIVER=zink")
        guest.add("LIBGL_KOPPER_DRI2=true")
        guest.addAll(profile.envLines())
        // The guest's own clocks, well inside the app's, so the app is never the first to give up.
        guest.add("DD_GPU_PROBE_ENUM_SECONDS=20")
        guest.add("DD_GPU_PROBE_SUBMIT_SECONDS=15")
        guest.add(SCRIPT)
        guest.add(profile.id)

        val command = LinuxRuntime.command(
            app, null, runtimeDir, Environment.getExternalStorageDirectory(), guest,
        )
        val hostEnv = ArrayList<String>()
        hostEnv.add("PROOT_LOADER=" + LinuxRuntime.prootLoader(app).path)
        hostEnv.add("PROOT_TMP_DIR=" + app.cacheDir.path)
        val prootLibs = LinuxRuntime.prootLibraryPath(app)
        if (prootLibs.isNotEmpty()) hostEnv.add("LD_LIBRARY_PATH=$prootLibs")

        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val done = CountDownLatch(1)
        var status: Int? = null
        var pid = -1
        try {
            pid = HostProcess.start(
                command.joinToString(" ") { it.replace("\\", "\\\\").replace(" ", "\\ ") },
                hostEnv.toTypedArray(), root,
                { value -> status = value; done.countDown() },
                { line -> lines.add(line) },
            )
        } catch (e: Exception) {
            val result = Result(true, "the probe could not be started: ${e.message}", lines,
                "DD_GPU_PROBE_SKIPPED: could not be started")
            GpuGuard.noteProbe(app, result)
            return result
        }
        if (pid <= 1) {
            val result = Result(true, "the probe process could not be started", lines,
                "DD_GPU_PROBE_SKIPPED: process could not be started")
            GpuGuard.noteProbe(app, result)
            return result
        }

        val finished = try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!finished) {
            GpuGuardLog.log("probe", "the probe did not finish in ${timeoutMs / 1000}s; taking it down")
            killTree(pid)
            runCatching { done.await(5, TimeUnit.SECONDS) }
            val result = Result(false,
                "the GPU did not answer the probe in ${timeoutMs / 1000}s (the process was still alive when the budget ran out)",
                lines + "DD_GPU_PROBE_SUBMIT_TIMEOUT: the probe process was still alive after ${timeoutMs / 1000}s",
                "DD_GPU_PROBE_SUBMIT_TIMEOUT: no answer in ${timeoutMs / 1000}s")
            GpuGuard.noteProbe(app, result)
            return result
        }
        killTree(pid)

        val exit = status ?: -1
        val result = when {
            exit == EXIT_TIMEOUT || exit == EXIT_KILLED -> Result(false,
                "the probe's own clock ran out (exit $exit)",
                lines + "DD_GPU_PROBE_SUBMIT_TIMEOUT: exit $exit",
                "DD_GPU_PROBE_SUBMIT_TIMEOUT: exit $exit")
            exit == 0 || exit == EXIT_FAILED -> Result.of(exit, ArrayList(lines))
            else -> Result(true, "the probe exited $exit; treated as a skip", lines,
                "DD_GPU_PROBE_SKIPPED: exit $exit")
        }
        GpuGuard.noteProbe(app, result)
        return result
    }

    /**
     * proot's `--kill-on-exit` only runs if proot gets to run it, and SIGKILL never lets it. So:
     * SIGTERM to proot, which takes its tracees with it, then SIGKILL to whatever is left of the
     * tree a moment later. Nothing here touches a process that is not under [rootPid].
     */
    private fun killTree(rootPid: Int) {
        signal(rootPid, 15)
        try {
            Thread.sleep(1200)
        } catch (e: InterruptedException) {
            return
        }
        for (pid in descendants(rootPid).sortedDescending()) signal(pid, 9)
        signal(rootPid, 9)
    }

    private fun descendants(root: Int): Set<Int> {
        val parent = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val stat = try {
                File(entry, "stat").readText()
            } catch (e: Exception) {
                return@forEach
            }
            stat.substringAfterLast(") ").split(' ').getOrNull(1)?.toIntOrNull()?.let { parent[pid] = it }
        }
        val out = HashSet<Int>()
        out.add(root)
        var grew = true
        while (grew) {
            grew = false
            for ((pid, ppid) in parent) if (ppid in out && out.add(pid)) grew = true
        }
        out.remove(root)
        return out
    }

    /** SIGTERM/SIGKILL by pid; `android.os.Process.sendSignal` is hidden, `killProcess` is SIGKILL. */
    private fun signal(pid: Int, which: Int) {
        if (pid <= 1) return
        try {
            Class.forName("android.os.Process")
                .getMethod("sendSignal", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(null, pid, which)
        } catch (e: Exception) {
            try {
                android.os.Process.killProcess(pid)
            } catch (ignored: Exception) {
            }
        }
    }

    /** The probe's own output, into the session folder, so a bug report carries it. */
    fun write(dir: File?, result: Result) {
        val file = dir?.let { File(it, "gpu-probe.log") } ?: return
        try {
            file.parentFile?.mkdirs()
            FileUtils.writeString(file, result.lines.joinToString("\n") + "\n")
        } catch (e: Exception) {
            GpuGuardLog.log("probe", "the probe log could not be written: $e")
        }
    }
}
