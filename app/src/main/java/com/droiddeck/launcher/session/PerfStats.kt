package com.droiddeck.launcher.session

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File

/**
 * The numbers behind the performance HUD that are not the frame rate: how much memory the device
 * has left, how close Android is to killing something to get some back, how busy the cpu is and
 * how far it has throttled, and how much memory the program whose frames are on screen is holding.
 *
 * Every reading here is one this app is allowed to make - /proc, /sys and the platform's own
 * managers; no root, no shelling out - and any of them can come back empty on some device. A
 * number that cannot be read is left out of the line rather than shown as a zero: these exist for
 * a person to act on, and "0 MB free" would be a lie that sends them looking in the wrong place.
 *
 * Read once a second, on the main thread. /proc/stat is a couple of kilobytes and the cpufreq
 * nodes are eight small files; the whole sample is a fraction of a millisecond.
 */
class PerfStats(context: Context) {

    /** What the kernel says about memory, in MiB. [threshold] is where Android starts killing. */
    class Memory(val availMb: Long, val totalMb: Long, val thresholdMb: Long, val low: Boolean)

    /** One reading of the cpu line of /proc/stat: jiffies in total, and jiffies spent idle. */
    private class Cpu(val total: Long, val idle: Long)

    /** PowerManager's thermal statuses, in the order Android numbers them. */
    companion object {
        const val MIB = 1_048_576L

        /** Of the jiffies between two readings, the share that was not idle, as a percentage. */
        fun busyPercent(prevTotal: Long, prevIdle: Long, nowTotal: Long, nowIdle: Long): Int {
            val dt = nowTotal - prevTotal
            if (dt <= 0L) return 0
            val idle = nowIdle - prevIdle
            return ((dt - idle) * 100L / dt).toInt().coerceIn(0, 100)
        }
    }

    private val app = context.applicationContext
    private val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val power =
        if (Build.VERSION.SDK_INT >= 29) runCatching { app.getSystemService(PowerManager::class.java) }.getOrNull()
        else null
    private val cpuDirs: List<File> = File("/sys/devices/system/cpu").listFiles { f ->
        f.isDirectory && f.name.matches(Regex("cpu\\d+"))
    }?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }.orEmpty()

    private var cpu: Cpu? = null

    /** How many cores the device has, from cpufreq's own directories when they are there. */
    val coreCount: Int
        get() = cpuDirs.size.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()

    fun memory(): Memory? {
        val manager = am ?: return null
        val info = ActivityManager.MemoryInfo()
        return try {
            manager.getMemoryInfo(info)
            Memory(info.availMem / MIB, info.totalMem / MIB, info.threshold / MIB, info.lowMemory)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * The share of the cpu that was busy since the previous call, 0-100, or -1 on the first call:
     * a percentage is a difference between two readings, and the first has nothing to differ from.
     *
     * This is counted from /proc/stat rather than read off /proc/loadavg, which every session log
     * on one device reported as 0.00 while frames stopped arriving and a title took another
     * 1.5 GB - a number that cannot be right, and one that cannot be checked from outside.
     */
    fun cpuPercent(): Int {
        val now = readCpu() ?: return -1
        val before = cpu
        cpu = now
        return if (before == null) -1 else busyPercent(before.total, before.idle, now.total, now.idle)
    }

    /** The fastest core's current frequency in MHz, or 0 where no cpufreq node can be read. */
    fun cpuMHz(): Int {
        var top = 0L
        for (dir in cpuDirs) {
            val khz = firstLong("${dir.path}/cpufreq/scaling_cur_freq") ?: continue
            if (khz > top) top = khz
        }
        return (top / 1000L).toInt()
    }

    /** Android's thermal status (PowerManager.THERMAL_STATUS_*), or -1 where it is not offered. */
    fun thermal(): Int = if (Build.VERSION.SDK_INT >= 29) {
        try {
            power?.currentThermalStatus ?: -1
        } catch (t: Throwable) {
            -1
        }
    } else {
        -1
    }

    /** Resident set of [pid] in MiB, or -1 when it is gone or is not this app's to read. */
    fun rssMb(pid: Int): Long {
        if (pid <= 0) return -1
        val text = try {
            File("/proc/$pid/status").readText()
        } catch (t: Throwable) {
            return -1
        }
        val kb = text.lineSequence()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.removePrefix("VmRSS:")
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull() ?: return -1
        return kb / 1024L
    }

    private fun readCpu(): Cpu? {
        val line = try {
            File("/proc/stat").useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } }
        } catch (t: Throwable) {
            null
        } ?: return null
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 8) return null
        val at = LongArray(fields.size - 1)
        for (i in 1 until fields.size) at[i - 1] = fields[i].toLongOrNull() ?: 0L
        // user, nice, system, idle, iowait, irq, softirq: a process waiting on the disk counts as
        // busy here, which is what a reader wants to know - iowait is work not getting done.
        val idle = at[3] + at[4]
        return Cpu(at[0] + at[1] + at[2] + idle + at[5] + at[6], idle)
    }

    private fun firstLong(path: String): Long? = try {
        File(path).readText().trim().toLongOrNull()
    } catch (t: Throwable) {
        null
    }
}
