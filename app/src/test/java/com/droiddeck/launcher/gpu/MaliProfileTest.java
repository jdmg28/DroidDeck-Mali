package com.droiddeck.launcher.gpu;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaliProfileTest {
    @Test public void gpuIsIdentifiedFromGpuNameNotCpuName() {
        assertEquals("Mali-G615 MC6", MaliProfile.name("Mali-G615 MC6"));
        assertEquals(615, MaliProfile.model("Mali-G615 6 cores r1p3 0xB8A3"));
        assertTrue(MaliProfile.isG615("Mesa Mali-G615 MC6"));
        assertFalse(MaliProfile.isG615("MediaTek Dimensity 8350-Ultimate"));
        assertEquals(0, MaliProfile.model(null));
        assertEquals(0, MaliProfile.model("Adreno 615"));
        assertEquals(720, MaliProfile.model("Immortalis-G720 MC12"));
    }
}
