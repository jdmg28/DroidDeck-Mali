package com.droiddeck.launcher.gpu;
import org.junit.Test;
import static org.junit.Assert.*;

public class DriverArchivePolicyTest {
    @Test public void acceptsDriverAndPrivateBackendOnlyByBasename() {
        assertTrue(DriverArchivePolicy.isMainLibrary("libvulkan_freedreno.so"));
        assertTrue(DriverArchivePolicy.isMainLibrary("libvulkan_panfrost.so"));
        assertTrue(DriverArchivePolicy.isMainLibrary("libvulkan_droiddeck_mali.so"));
        assertFalse(DriverArchivePolicy.isMainLibrary("../libvulkan_panfrost.so"));
        assertFalse(DriverArchivePolicy.isMainLibrary("libvulkan_panfrost.so/evil"));
        assertFalse(DriverArchivePolicy.isMainLibrary("libvulkan_panfrost.so\\evil"));
        assertFalse(DriverArchivePolicy.isMainLibrary("libc.so.6"));
        assertFalse(DriverArchivePolicy.isMainLibrary(null));
        assertTrue(DriverArchivePolicy.isBackendLibrary("libpanvk_backend.so"));
        assertFalse(DriverArchivePolicy.isBackendLibrary("libmali.so"));
        assertTrue(DriverArchivePolicy.requiresBackend("libvulkan_droiddeck_mali.so"));
        assertFalse(DriverArchivePolicy.requiresBackend("libvulkan_freedreno.so"));
    }
    @Test public void namesTheVendorFromTheLibraryTheZipCarried() {
        // The file on disk is renamed to libvulkan_freedreno.so for every driver, so the vendor has
        // to come from the name in the zip - and "freedreno" there means Adreno, not "the file the
        // importer writes", which is how this distinction would otherwise be lost.
        assertEquals("software", DriverArchivePolicy.gpuVendor("libvulkan_lvp.so"));
        assertEquals("adreno", DriverArchivePolicy.gpuVendor("libvulkan_freedreno.so"));
        assertEquals("mali", DriverArchivePolicy.gpuVendor("libvulkan_panfrost-glibc-aarch64.so"));
        assertEquals("mali", DriverArchivePolicy.gpuVendor("libvulkan_droiddeck_mali.so"));
        assertEquals("", DriverArchivePolicy.gpuVendor("libvulkan_something_else.so"));
        assertEquals("", DriverArchivePolicy.gpuVendor(null));
        assertTrue(DriverArchivePolicy.isSoftwareLibrary("libvulkan_lvp.so"));
        // The main library rule is unchanged by all this: a software ICD is importable as-is.
        assertTrue(DriverArchivePolicy.isMainLibrary("libvulkan_lvp.so"));
    }
    @Test public void acceptsVersionedSonamesAsCompanionsAndNothingElse() {
        assertTrue(DriverArchivePolicy.isCompanionLibrary("libLLVM.so.19.1"));
        assertTrue(DriverArchivePolicy.isCompanionLibrary("libstdc++.so.6"));
        assertTrue(DriverArchivePolicy.isCompanionLibrary("libgcc_s.so.1"));
        assertFalse("a main library is never a companion", DriverArchivePolicy.isCompanionLibrary("libvulkan_lvp.so"));
        assertFalse("the PanVK backend has its own rule", DriverArchivePolicy.isCompanionLibrary("libpanvk_backend.so"));
        assertFalse("no extension, no", DriverArchivePolicy.isCompanionLibrary("libLLVM"));
        assertFalse("not a version suffix", DriverArchivePolicy.isCompanionLibrary("libLLVM.so.txt"));
        assertFalse("not a shell/profile script", DriverArchivePolicy.isCompanionLibrary("ld.so.preload.bak"));
        assertFalse(DriverArchivePolicy.isCompanionLibrary(null));
    }
    @Test public void keepsLegacyIcdVersionUnlessValidPackageVersionIsGiven() {
        assertEquals("1.4.363", DriverArchivePolicy.apiVersion("1.4.363"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion(null));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("1.4.9999"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("1.4.363\n"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("9.9.999"));
    }
}
