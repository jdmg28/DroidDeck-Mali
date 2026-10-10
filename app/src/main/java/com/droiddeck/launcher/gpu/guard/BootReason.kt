/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu.guard

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

    class Reading(val raw: String, val kind: Kind) {
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

    fun read(): Reading {
        for (key in KEYS) {
            val value = systemProperty(key)
            if (value.isBlank()) continue
            return Reading(value.trim(), classify(value))
        }
        Log.i(TAG, "boot reason: no property under any of $KEYS could be read")
        return Reading("", Kind.UNKNOWN)
    }

    /**
     * A reboot reason to a [Kind]. Deliberately broad: vendors spell the same event several ways
     * (`reboot,kernel_panic`, `kernel_panic,watchdog`, `HWWDT`, `thermal_shutdown`), and missing a
     * real panic is the expensive mistake here while treating an ordinary reboot as fatal only
     * costs the user one confirmation.
     */
    fun classify(raw: String): Kind {
        val text = raw.lowercase()
        // Panic first: "kernel_panic,watchdog" is a panic that a watchdog then finished, and the
        // panic is the part that says the GPU driver was involved.
        if (text.contains("panic") || text.contains("oops") || text.contains("bug(")) return Kind.KERNEL_PANIC
        if (text.contains("watchdog") || text.contains("wdog") || text.contains("hwwt") ||
            text.contains("hw watchdog") || text.contains("hwdt")
        ) return Kind.WATCHDOG
        if (text.contains("thermal") || text.contains("overheat") || text.contains("overtemp") ||
            text.contains("shutdown,therm")
        ) return Kind.THERMAL
        if (text.contains("reboot") || text.contains("power_key") || text.contains("powerkey") ||
            text.contains("poweron") || text.contains("cold") || text.contains("rtc") ||
            text.contains("alarm") || text.contains("adb") || text.contains("shell") ||
            text.contains("bootloader") || text.contains("recovery") || text.contains("factory") ||
            text.contains("charger") || text.contains("usb") || text.contains("userrequested") ||
            text.contains("key_power") || text.contains("kpdpwr") || text.contains("normal")
        ) return Kind.NORMAL
        return Kind.UNKNOWN
    }

    /** android.os.SystemProperties.get, which apps may read but not call directly. */
    fun systemProperty(name: String): String = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    } catch (e: Exception) {
        ""
    }
}
