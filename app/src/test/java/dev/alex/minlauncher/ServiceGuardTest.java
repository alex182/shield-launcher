package dev.alex.minlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ServiceGuardTest {

    private static final String US = ServiceGuard.SERVICE;
    private static final String SHORT = "dev.alex.minlauncher/.QuickMenuService";
    private static final String MAPPER = "flar2.homebutton/.utils.MyAccessibilityService";

    @Test
    public void anEmptyOrUnsetListGetsJustOurService() {
        assertEquals(US, ServiceGuard.with(null, US));
        assertEquals(US, ServiceGuard.with("null", US));
        assertEquals(US, ServiceGuard.with("", US));
    }

    @Test
    public void otherAppsServicesAreKept() {
        assertEquals(MAPPER + ":" + US, ServiceGuard.with(MAPPER, US));
        assertEquals(MAPPER, ServiceGuard.without(MAPPER + ":" + US, US));
        assertEquals(MAPPER, ServiceGuard.without(US + ":" + MAPPER, US));
    }

    @Test
    public void theShortFormCountsAsOurs() {
        assertTrue(ServiceGuard.contains(SHORT, US));
        assertTrue(ServiceGuard.contains(MAPPER + ":" + SHORT, US));
        assertEquals(SHORT, ServiceGuard.with(SHORT, US));
        assertEquals("", ServiceGuard.without(SHORT, US));
    }

    @Test
    public void otherServicesInOurPackageAreNotOurs() {
        assertFalse(ServiceGuard.contains("dev.alex.minlauncher/.Other", US));
        assertFalse(ServiceGuard.contains(MAPPER, US));
    }
}
