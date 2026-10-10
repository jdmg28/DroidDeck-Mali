/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu;

/** Pure filename/API policy for the experimental Linux driver import. No Android dependency. */
public final class DriverArchivePolicy {
    public static final String BACKEND_NAME = "libpanvk_backend.so";
    public static final long MAX_ARCHIVE_BYTES = 128L << 20;
    public static final long MAX_LIBRARY_BYTES = 96L << 20;
    public static final int MAX_META_BYTES = 64 << 10;
    private DriverArchivePolicy() {}

    public static boolean isMainLibrary(String name) {
        return name != null && name.matches("libvulkan_[A-Za-z0-9][A-Za-z0-9_.-]*\\.so");
    }
    public static boolean isBackendLibrary(String name) {
        return BACKEND_NAME.equals(name);
    }
    public static boolean requiresBackend(String sourceLibraryName) {
        return "libvulkan_droiddeck_mali.so".equals(sourceLibraryName);
    }
    /**
     * Which GPU a driver drives, from the library name the zip carried. The file on disk is always
     * {@link LinuxVulkanDriverManager#LIB_NAME} - the importer renames, so this has to be asked
     * before that - and it is what makes a software driver distinguishable from a hardware one
     * after the fact.
     */
    public static String gpuVendor(String sourceLibraryName) {
        if (sourceLibraryName == null) return "";
        if (sourceLibraryName.startsWith("libvulkan_lvp")) return "software";
        if (sourceLibraryName.startsWith("libvulkan_panfrost") || requiresBackend(sourceLibraryName)) return "mali";
        if (sourceLibraryName.startsWith("libvulkan_freedreno")) return "adreno";
        return "";
    }

    public static boolean isSoftwareLibrary(String name) {
        return "software".equals(gpuVendor(name));
    }

    /**
     * A driver's own dependency, shipped beside it: Mesa's lavapipe needs libLLVM, and the only
     * thing that makes a relocated .so find a library next to it is an RPATH of {@code $ORIGIN},
     * which the packaging step writes with patchelf. Accepted by shape only - a versioned soname -
     * and cross-checked against meta.json in {@code adopt()}, so a zip cannot drop files into the
     * app's directory on a hunch.
     */
    public static boolean isCompanionLibrary(String name) {
        return name != null && name.matches("lib[A-Za-z0-9][A-Za-z0-9_.+-]*\\.so(\\.[0-9]+)*")
                && !isMainLibrary(name) && !isBackendLibrary(name);
    }

    public static final int MAX_COMPANIONS = 4;

    public static boolean isMaliRuntimeLibrary(String name) {
        return requiresBackend(name) || (isMainLibrary(name) && name.startsWith("libvulkan_panfrost"));
    }
    /** Keep the old Turnip manifest default unless the package supplies a valid Vulkan 1.x version. */
    public static String apiVersion(String value) {
        if (value == null || value.length() > 16 || !value.matches("1\\.[0-4]\\.[0-9]{1,4}")) return "1.1.274";
        String[] parts = value.split("\\.");
        if (Integer.parseInt(parts[2]) > 4095) return "1.1.274";
        return value;
    }
}
