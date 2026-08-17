package com.nexo.gametunnel.model;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TunnelSnapshotTest {
    @Test
    public void disconnectingStillBlocksProfileChanges() {
        assertTrue(snapshot(TunnelSnapshot.Status.DISCONNECTING).isActive());
    }

    @Test
    public void terminalStatesDoNotCountAsActive() {
        assertFalse(snapshot(TunnelSnapshot.Status.DISCONNECTED).isActive());
        assertFalse(snapshot(TunnelSnapshot.Status.ERROR).isActive());
    }

    private static TunnelSnapshot snapshot(final TunnelSnapshot.Status status) {
        return new TunnelSnapshot(status, "Detalle", "—", 0L, 0L, 0L, 0, true);
    }
}
