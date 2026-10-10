/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu.guard

import android.content.Context
import android.util.Log

/**
 * Why the phone started the last time, as Android itself recorded it.
 *
 * This is the whole basis of the safety latch. When the Mali kernel module takes the device down
 * (`kbasep_csf_sync_kcpu_dump_locked` -> `Unexpected kernel BRK exception at EL1` -> panic), the
 * app is not running and cannot see it happen. What it CAN see, the next time it starts, is that
 * Android recorded the boot as `kernel_panic` rather than as a power-on or a user-requested
 * reboot. That single property is what turns "the phone rebooted while using Steam" from an
 * unexplainable event into a fact the app can act on: refuse to walk straight back into the same
 * workload.
 *
 * `sys.boot.reason` is set by the init that ran on that boot and is readable by any app through
 * the (hidden but unblocked) `android.os.SystemProperties` getter; the fallback keys cover vendors
 * that keep the same value somewhere else.
 */
object BootReason {
    private const val TAG = "BootReason"

    enum class Kind {
        /** A power-on, a `adb reboot`, a long-press: nothing went wrong. */
        NORMAL,

        /** The kernel died: a panic, an oops that took the device with it, a BUG(). */
        KERNEL_PANIC,

        /** A hardware or framework watchdog bit: something stopped feeding it. */
        WATCHDOG,

        /** The device restarted because it was too hot. */
        THERMAL,

        /** The property could not be read, or says something we do not recognise. */
        UNKNOWN,
    }

    class Reading(val raw: String, val kind: Kind, val source: String = "") {
        /** Whether this boot is evidence that the previous one ended in a crash. */
        val fatal: Boolean
            get() = kind == Kind.KERNEL_PANIC || kind == Kind.WATCHDOG || kind == Kind.THERMAL

        override fun toString(): String = if (raw.isEmpty()) kind.name else "$raw [$kind]"
    }

    /**
     * The properties that hold it, in the order they are trusted. `sys.boot.reason` is Android 9+
     * and is the one the platform itself uses for `DropBoxManager`'s reboot tags; the others are
     * what MediaTek and a few others leave behind.
     */
    private val KEYS = listOf(
        "sys.boot.reason",
        "persist.sys.boot.reason",
        "sys.boot.reason.last",
        "ro.boot.bootreason",
        "vendor.sys.boot.reason",
    )

    fun read(): Reading = read(null)

    /**
     * Why the phone started the last time, with every source tried in turn.
     *
     * [systemProperty] goes first because it is free, but it is a hidden API and on current
     * Android it is refused: `android.os.SystemProperties` is not in the SDK and reflection into
     * it returns nothing on a modern device, which is exactly what happened on the Mali-G615 this
     * was written for - the property came back empty for all five keys and the latch never knew a
     * crash had happened. So the same properties are then read the way a shell would read them,
     * by running `getprop`, which is an ordinary unprivileged binary and is not hidden. Last comes
     * what the platform is willing to say through the SDK about the previous shutdown.
     */
    fun read(context: android.content.Context?): Reading {
        for (key in KEYS) {
            val value = systemProperty(key)
            if (value.isBlank()) continue
            return Reading(value.trim(), classify(value), "SystemProperties $key")
        }

        val properties = getprop()
        if (properties.isNotEmpty()) {
            for (key in KEYS) {
                val value = properties[key]
                if (value.isNullOrBlank()) continue
                return Reading(value.trim(), classify(value), "getprop $key")
            }
        }

        if (context != null) {
            val shutdown = lastShutdownReason(context)
            if (shutdown != null) {
                return Reading(shutdown, classify(shutdown), "PowerManager.getLastShutdownReason")
            }
        }

        Log.i(TAG, "boot reason: no property under any of $KEYS could be read by any means " +
            "(SystemProperties refused, getprop ${if (properties.isEmpty()) "unavailable" else "had none of them"})")
        return Reading("", Kind.UNKNOWN, "not readable")
    }

    /**
     * Every property the phone has, read by running `getprop` once.
     *
     * This is the fallback that matters. A hidden API can be closed; `/system/bin/getprop` is a
     * binary any app may execute, and it reads the very same property area. It is parsed into a
     * map so one process start covers every key and every later question.
     */
    fun getprop(): Map<String, String> {
        val out = HashMap<String, String>()
        for (command in listOf("/system/bin/getprop", "getprop")) {
            try {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    continue
                }
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        // "[ro.foo]: [bar]"
                        val start = line.indexOf('[')
                        if (start < 0) continue
                        val middle = line.indexOf("]: [", start)
                        if (middle < 0) continue
                        val end = line.lastIndexOf(']')
                        if (end <= middle) continue
                        out[line.substring(start + 1, middle)] = line.substring(middle + 4, end)
                    }
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                Log.i(TAG, "getprop: $command did not run: $e")
            }
        }
        return out
    }

    /**
     * What Android recorded as the reason for the last shutdown, through the SDK. It does not name
     * a kernel panic - a panic is not a shutdown - but it does name the ordinary cases, and
     * `UNKNOWN` after a restart we know was not requested is itself worth recording next to
     * everything else.
     *
     * Reached by reflection, with the constants looked up by name, because the method arrived in a
     * later API level than this app compiles against: a symbol the compiler cannot resolve is a
     * build that does not run at all, which is a worse outcome than one source fewer.
     */
    fun lastShutdownReason(context: Context): String? {
        if (android.os.Build.VERSION.SDK_INT < 30) return null
        return try {
            val manager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                ?: return null
            val value = android.os.PowerManager::class.java
                .getMethod("getLastShutdownReason").invoke(manager) as? Int ?: return null
            for ((name, label) in SHUTDOWN_REASONS) {
                val constant = try {
                    android.os.PowerManager::class.java.getField(name).getInt(null)
                } catch (e: Exception) {
                    continue
                }
                if (constant == value) return label
            }
            null
        } catch (e: Exception) {
            Log.i(TAG, "last shutdown reason: not available ($e)")
            null
        }
    }

    /** android.os.SystemProperties.get, which apps may read but not call directly. */
    fun systemProperty(name: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    } catch (e: Exception) {
        ""
    }
}
