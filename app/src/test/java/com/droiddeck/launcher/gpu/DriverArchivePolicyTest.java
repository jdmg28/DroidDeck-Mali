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
    @Test public void keepsLegacyIcdVersionUnlessValidPackageVersionIsGiven() {
        assertEquals("1.4.363", DriverArchivePolicy.apiVersion("1.4.363"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion(null));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("1.4.9999"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("1.4.363\n"));
        assertEquals("1.1.274", DriverArchivePolicy.apiVersion("9.9.999"));
    }
}
