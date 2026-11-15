package com.droiddeck.launcher.core

import com.droiddeck.launcher.session.SessionPrefs

object GameEnvironment {
    data class Config(
        val shared: Map<String, String?> = emptyMap(),
        val games: Map<String, Map<String, String?>> = emptyMap(),
    ) {
        fun entries(scope: String) = if (scope.isEmpty()) shared else games[scope].orEmpty()

        fun withEntries(scope: String, entries: Map<String, String?>): Config =
            if (scope.isEmpty()) copy(shared = entries.toMap())
            else copy(games = if (entries.isEmpty()) games - scope else games + (scope to entries.toMap()))
    }

    private val namePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val unused = setOf(
        "WINENTSYNC", "WINE_FAST_YIELD", "WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER",
        "WINE_NEW_MEDIASOURCE", "WRAPPER_MAX_IMAGE_COUNT", "WRAPPER_DMAHEAP_CACHED",
        "ALSA_LATENCY_MS", "ALSA_VOLUME", "ALSA_BASS_BOOST", "ALSA_PERFORMANCE_MODE",
        "DXVK_ASYNC", "DXVK_GPLASYNCCACHE",
    )

    fun validName(name: String) = namePattern.matches(name)
    fun supported(name: String) = name !in unused && !name.startsWith("BOX86_")
    fun validValue(value: String) = '\u0000' !in value && value.length <= 8192
    fun validScope(scope: String) = scope.isEmpty() || (Regex("[1-9][0-9]*").matches(scope) && scope.toLongOrNull()?.let { it in 1..4294967295L } == true)

    fun defaults(preset: String, forceSsbs: Boolean = false): Map<String, String?> = linkedMapOf<String, String?>(
        "MESA_SHADER_CACHE_DISABLE" to "false",
        "DROIDDECK_ANTICHEAT_OFFLINE" to "1",
        "DOTNET_GCRegionRange" to "800000000",
        "MESA_SHADER_CACHE_MAX_SIZE" to "512M",
        "WINE_SKIP_OPENGL_GPU_PROBE" to "1",
        "DISABLE_VK_LAYER_VALVE_steam_fossilize_1" to "1",
        "heap_memory_percent" to "0.12",
        "VKD3D_FEATURE_LEVEL" to "12_2",
        "VKD3D_SHADER_MODEL" to "6_9",
        // Cap Wine virtual memory allocations (supported by DroidDeck-Proton / Bannerlator Wine) so
        // runaway game allocations fail gracefully instead of triggering Android LMK.
        "WINEVMEMMAXSIZE" to "4096",
        // .NET 7 and later reserve 256 GB of address space for the GC heap at startup, more than an
        // Android process has free, and the runtime fails to start. 32 GB is plenty for a game.
        "DOTNET_GCRegionRange" to "0x800000000",
        // .NET's W^X writes JIT code through a second mapping that FEX does not watch, so FEX keeps
        // running the stale translation and the game hangs at startup.
