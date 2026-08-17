package com.nexo.gametunnel.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.wireguard.config.Config;

import org.junit.Test;

public final class WireGuardConfigsTest {
    private static final String PRIVATE_KEY =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String PUBLIC_KEY =
            "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=";
    private static final String PRESHARED_KEY =
            "AgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICE=";

    private static String sample() {
        return "[Interface]\n"
                + "PrivateKey = " + PRIVATE_KEY + "\n"
                + "Address = 10.66.66.2/32\n"
                + "DNS = 1.1.1.1\n"
                + "MTU = 1280\n\n"
                + "[Peer]\n"
                + "PublicKey = " + PUBLIC_KEY + "\n"
                + "Endpoint = 203.0.113.10:51820\n"
                + "AllowedIPs = 0.0.0.0/0\n"
                + "PersistentKeepalive = 25\n";
    }

    @Test
    public void selectedApplicationIsExclusive() throws Exception {
        final Config config = WireGuardConfigs.forApplication(
                sample(), "com.proximabeta.mf.uamo");

        assertEquals(1, config.getInterface().getIncludedApplications().size());
        assertTrue(config.getInterface().getIncludedApplications()
                .contains("com.proximabeta.mf.uamo"));
        assertTrue(config.getInterface().getExcludedApplications().isEmpty());
    }

    @Test
    public void manualProfilePreservesOptionalPresharedKey() throws Exception {
        final String normalized = WireGuardConfigs.createManual(
                PRIVATE_KEY,
                "10.66.66.2/32",
                "1.1.1.1, 1.0.0.1",
                PUBLIC_KEY,
                PRESHARED_KEY,
                "203.0.113.10",
                "51820",
                "0.0.0.0/0",
                "25",
                "1280");

        assertTrue(normalized.contains("PreSharedKey = " + PRESHARED_KEY));
        assertTrue(normalized.contains("MTU = 1280"));
        assertEquals(1, WireGuardConfigs.parse(normalized).getPeers().size());
    }

    @Test
    public void rejectsEmptyAndOversizedInput() {
        assertThrows(IllegalArgumentException.class, () -> WireGuardConfigs.parse("  "));
        assertThrows(IllegalArgumentException.class,
                () -> WireGuardConfigs.parse("x".repeat(WireGuardConfigs.MAX_CONFIG_BYTES + 1)));
    }

    @Test
    public void validatesAndroidPackageNames() {
        assertTrue(WireGuardConfigs.isPackageName("com.proximabeta.mf.uamo"));
        assertFalse(WireGuardConfigs.isPackageName("not-a-package"));
        assertFalse(WireGuardConfigs.isPackageName(""));
    }
}
