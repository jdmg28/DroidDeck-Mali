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
    @Test public void recognisesTheSoftwareDriverAndTheLlvmItLoads() {
        assertTrue(DriverArchivePolicy.isSoftwareLibrary("libvulkan_lvp.so"));
        assertFalse(DriverArchivePolicy.isSoftwareLibrary("libvulkan_freedreno.so"));
        assertFalse(DriverArchivePolicy.isSoftwareLibrary("libvulkan_lvp.so.bak"));
        // Only the names Debian's libllvm19 ships, so a zip cannot smuggle in an arbitrary .so.
        assertTrue(DriverArchivePolicy.isRuntimeLibrary("libLLVM.so.19.1"));
        assertTrue(DriverArchivePolicy.isRuntimeLibrary("libLLVM-19.so.1"));
        assertFalse(DriverArchivePolicy.isRuntimeLibrary("libLLVM.so.18.1"));
        assertFalse(DriverArchivePolicy.isRuntimeLibrary("libc.so.6"));
        assertFalse(DriverArchivePolicy.isRuntimeLibrary(null));
        assertFalse(DriverArchivePolicy.isRuntimeLibrary("../../libLLVM.so.19.1"));
        // What Debian's libLLVM loads on top of it, measured against the runtime: libedit, and
        // through it libtinfo and libbsd.
        assertTrue(DriverArchivePolicy.isRuntimeLibrary("libedit.so.2"));
        assertTrue(DriverArchivePolicy.isRuntimeLibrary("libtinfo.so.6"));
        assertTrue(DriverArchivePolicy.isRuntimeLibrary("libbsd.so.0"));
        assertFalse(DriverArchivePolicy.isRuntimeLibrary("libedit.so"));
        assertFalse(DriverArchivePolicy.isRuntimeLibrary("libvulkan.so.1"));
        assertFalse(DriverArchivePolicy.isRuntimeLibrary("libLLVM.so.19.1.so"));
        // A GPU driver is not a software one, and neither claims the other's dependencies.
        assertFalse(DriverArchivePolicy.isMaliRuntimeLibrary("libvulkan_lvp.so"));
        assertFalse(DriverArchivePolicy.requiresBackend("libvulkan_lvp.so"));
    }

    @Test public void keepsLegacyIcdVersionUnlessValidPackageVersionIsGiven() {
        assertEquals("1.4.363", DriverArchivePolicy.apiVersion("1.4.363"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion(null));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("1.4.9999"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("1.4.363\n"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("9.9.999"));
    }
}
