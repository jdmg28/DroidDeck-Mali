/* SPDX-License-Identifier: GPL-3.0-only */
package com.droiddeck.launcher.gpu;

/** Pure filename/API policy for the experimental Linux driver import. No Android dependency. */
public final class DriverArchivePolicy {
    public static final String BACKEND_NAME = "libpanvk_backend.so";
    /**
     * What one driver package may write, unpacked. The number is Debian's rather than ours: the software
     * (lavapipe) package carries {@code libLLVM.so.19.1} at 123,242,120 bytes next to a 10,451,865 byte
     * driver, 133,695,570 unpacked, and the 128 MiB this used to carry left 522 KB of margin - a
     * package that fits by 0.4% is a package that stops fitting at the next Mesa revision. A limit that
     * cannot hold the one library a mode depends on is not a safety limit either; it is an outage with a
     * error dialog.
     */
    public static final long MAX_ARCHIVE_BYTES = 256L << 20;
    /** One library, unpacked. 96 MiB was fine for Turnip and refused Debian's LLVM by 22 MB. */
    public static final long MAX_LIBRARY_BYTES = 192L << 20;
    public static final int MAX_META_BYTES = 64 << 10;
    /** The headroom {@link #requiredFreeSpace} always asks for, whatever the zip claims. */
    public static final long MIN_FREE_BYTES = 32L << 20;
    private DriverArchivePolicy() {}

    /**
     * Free space an import of this archive size should be allowed to need.
     *
     * Raising the size ceilings without asking this would have been half the change: the importer writes
     * into the app's own files directory, so on a nearly full device the failure mode of a 133 MB driver
     * is not a clean refusal but a partition filled mid-extraction. A {@code .so} of this shape
     * compresses about 3.3x - 40.6 MB of zip for 133.7 MB written - so four times the archive plus a
     * floor is comfortably more than the files inside it and much less than a device with room to spare
     * would notice. The answer is capped by the extraction ceiling, because nothing above it can be
     * written here at all.
     *
     * @param archiveBytes the size of the zip being imported, or 0 when it could not be measured
     *                     (a content provider that will not say is not a reason to refuse an import)
     */
    public static long requiredFreeSpace(long archiveBytes) {
        if (archiveBytes <= 0) return 0;
        if (archiveBytes > Long.MAX_VALUE / 4) return MAX_ARCHIVE_BYTES;
        return Math.min(archiveBytes * 4 + MIN_FREE_BYTES, MAX_ARCHIVE_BYTES);
    }

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
