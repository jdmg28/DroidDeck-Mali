/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu.guard

/**
 * How far the guard is allowed to change a session to keep the GPU out of trouble.
 *
 * None of this fixes the Mali kernel module - nothing in an APK can, and pretending otherwise
 * would be the dangerous thing here. What it does is shrink everything that pushes the experimental
 * PanVK-kbase path harder than it has ever been proven to go: fewer pixels leaves fewer and
 * smaller allocations for a driver to get wrong, a frame cap leaves the GPU idle half of every
 * second instead of chasing the panel, and turning off HDR, frame generation, the upscaler and the
 * realtime queues removes four separate paths that each submit work the driver has never been
 * validated on.
 *
 * [STRICT] additionally moves the Steam client's own interface off the GPU entirely (llvmpipe
 * instead of Zink). That is slow, and it is meant to be diagnostic: a session that survives on
 * [STRICT] but dies on [SAFE] is a session whose fault is in the Vulkan path.
 */
enum class SafeProfile(
    val id: String,
    /** Most pixels gamescope may be asked for, long side times short side; 0 = whatever was set. */
    val maxPixels: Int,
    /** Highest frame cap allowed; 0 = no cap. A session with no cap keeps the cap given here. */
    val maxFps: Int,
    /** HDR10 output: a 10-bit swapchain and its colour-management path. */
    val allowsHdr: Boolean,
    /** LSFG / Win-FG: extra Vulkan work in the compositor on top of the session's. */
    val allowsFrameGen: Boolean,
    /** The compositor's upscaler and sharpening passes. */
    val allowsUpscaling: Boolean,
    /** CAS, fake-HDR, debanding and the colour grade. */
    val allowsScreenEffects: Boolean,
    /** Holding the GPU at its top clock (GpuClockPin); on Mali there is no KGSL to pin anyway. */
    val allowsGpuClockPin: Boolean,
    /** The performance overlay, which polls GPU sysfs from inside the session. */
    val allowsMangoapp: Boolean,
    /** gamescope's realtime-priority Vulkan queues. */
    val allowsRealtimeQueues: Boolean,
    /** Presenting the session on a second display as well. */
    val allowsSecondScreen: Boolean,
    /** Draw the client's interface on the CPU: `GALLIUM_DRIVER=llvmpipe` instead of Zink. */
    val softwareUi: Boolean,
    /** Stop Mesa keeping a shader cache: a bad cached program is one more unvalidated input. */
    val disableShaderCache: Boolean,
) {
    /** Nothing is changed; the guard only watches and reports. */
    OFF(
        id = GpuSafetyPrefs.PROFILE_OFF, maxPixels = 0, maxFps = 0,
        allowsHdr = true, allowsFrameGen = true, allowsUpscaling = true, allowsScreenEffects = true,
        allowsGpuClockPin = true, allowsMangoapp = true, allowsRealtimeQueues = true,
        allowsSecondScreen = true, softwareUi = false, disableShaderCache = false,
    ),

    /**
     * The everyday setting on an experimental GPU: 720p at 30, and every extra GPU path off.
     * Steam's Big Picture is usable at 720p - it is what the Deck itself rendered at for years.
     */
    SAFE(
        id = GpuSafetyPrefs.PROFILE_SAFE, maxPixels = 1280 * 720, maxFps = 30,
        allowsHdr = false, allowsFrameGen = false, allowsUpscaling = true, allowsScreenEffects = true,
        allowsGpuClockPin = false, allowsMangoapp = false, allowsRealtimeQueues = false,
        allowsSecondScreen = true, softwareUi = false, disableShaderCache = false,
    ),

    /**
     * What runs after the device has crashed once: 576p at 24, every extra path off, no shader
     * cache, and the client's interface drawn on the CPU so only games touch the GPU at all.
     */
    STRICT(
        id = GpuSafetyPrefs.PROFILE_STRICT, maxPixels = 1024 * 576, maxFps = 24,
        allowsHdr = false, allowsFrameGen = false, allowsUpscaling = false, allowsScreenEffects = false,
        allowsGpuClockPin = false, allowsMangoapp = false, allowsRealtimeQueues = false,
        allowsSecondScreen = false, softwareUi = true, disableShaderCache = true,
    ),
    ;

    /**
     * [size] brought down to this profile's pixel ceiling, keeping its shape. "Follow screen" is
     * left alone: the output tracks the panel there, and the guard's own cap would fight the resize.
     */
    fun constrained(size: Pair<Int, Int>, followScreen: Boolean): Pair<Int, Int> {
        if (maxPixels <= 0 || followScreen) return size
        val pixels = size.first * size.second
        if (pixels <= maxPixels) return size
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
        val w = (size.first * scale).toInt().coerceAtLeast(320) and 1.inv()
        val h = (size.second * scale).toInt().coerceAtLeast(240) and 1.inv()
        return Pair(w, h)
    }

    /** The frame cap to run with: the session's, brought down to this profile's ceiling. */
    fun clampedFps(fps: Int): Int = when {
        maxFps <= 0 -> fps
        fps <= 0 -> maxFps
        else -> minOf(fps, maxFps)
    }

    /**
     * Environment for the guest. Only keys that nothing else in the session sets, so the order
     * they arrive in the guest's environment cannot decide which one wins - the switches that do
     * collide (GALLIUM_DRIVER, MESA_LOADER_DRIVER_OVERRIDE, BL_GAMESCOPE_REALTIME, BL_MANGOAPP)
     * are handled where they are built, in SessionService.
     */
    fun envLines(): List<String> {
        val lines = mutableListOf("BL_MALI_GUARD=$id")
        if (this != OFF) lines.add("BL_MALI_GUARD_MAX_PIXELS=$maxPixels")
        if (this != OFF) lines.add("BL_MALI_GUARD_MAX_FPS=$maxFps")
        if (disableShaderCache) lines.add("MESA_SHADER_CACHE_DISABLE=1")
        return lines
    }

    companion object {
        /** The profile [id] names, [SAFE] when it is not one: a stale preference must never throw. */
        fun of(id: String?): SafeProfile = values().firstOrNull { it.id == id } ?: SAFE
    }
}
