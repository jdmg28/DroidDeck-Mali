package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import java.io.File

/**
 * Whether this device can draw a Linux session at all.
 *
 * The runtime draws with Turnip, an Adreno driver. On Mali, Xclipse and PowerVR the compositor
 * gets no usable Vulkan device and a session comes up as sound over a black screen - a failure
 * with nothing in it to read. The stock app cannot fix that. This experimental build adds Mali detection
 * and an explicitly imported PanVK-kbase path; it does not replace any vendor driver. Adreno is recognised by what only Qualcomm's stack has: the KGSL node, or the
 * vendor's own Vulkan driver at its usual path.
 */
object DeviceSupport {
    private val adrenoGpu by lazy {
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
    }

    fun adreno(): Boolean = adrenoGpu

    fun graphicsAvailable(): Boolean = graphicsAvailable(adreno(), SystemVulkanDriver.isAvailable())

    internal fun graphicsAvailable(adreno: Boolean, powerVrReady: Boolean): Boolean = adreno || powerVrReady

    fun vkBridge(context: Context): Boolean =
        bridgeUsable() && !com.droiddeck.launcher.session.VenusComponent.wanted(context) &&
            com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)

    fun mali(): Boolean = isMali

    private val isMali by lazy {
        !adreno() && (File("/dev/mali0").exists() ||
            com.droiddeck.launcher.gpu.GpuInfo.detect().family.name.startsWith("MALI"))
    }

    fun bridgeUsable(): Boolean = !adreno() && !SystemVulkanDriver.isAvailable() && File("/dev/mali0").exists()

    fun openDriverActive(context: Context): Boolean {
        if (adreno()) return false
        if (com.droiddeck.launcher.session.VenusComponent.wanted(context)) return false
        if (bridgeUsable() && com.droiddeck.launcher.session.SessionPrefs.vkBridge(context)) return false
        val linux = com.droiddeck.launcher.session.SessionPrefs.linuxDriver(context).ifEmpty { return false }
        return com.droiddeck.launcher.gpu.DriverPairs.isNonAdreno(com.droiddeck.launcher.gpu.DriverBundle.containing(context, linux, true)?.id)
    }

    fun canDraw(context: Context): Boolean = graphicsAvailable() || openDriverActive(context) || vkBridge(context) ||
        com.droiddeck.launcher.session.VenusComponent.wanted(context)

    /** Detection is not proof that the stock kbase ABI/permissions allow PanVK. */
    @JvmStatic
    fun mali(): Boolean = !adreno() && (
        File("/dev/mali0").exists() || File("/sys/class/misc/mali0").exists() ||
        File("/vendor/lib64/hw/vulkan.mali.so").exists() ||
        File("/vendor/lib64/egl/libGLES_mali.so").exists())

    /**
     * Every Mali device the driver could serve, not one SoC or one Android release: the session
     * draws through the imported PanVK ICD either way, and an unsupported generation is the
     * session's clean error to give, not a locked app.
     */
    fun canTry(): Boolean = adreno() || mali()

    /** The chip as the device names it, for the card that explains the refusal. */
    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
