package com.nexo.gametunnel.model;

import java.util.Objects;

/** Immutable state rendered by the UI. It never contains private configuration material. */
public final class TunnelSnapshot {
    public enum Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        NO_NETWORK,
        RECOVERING,
        DISCONNECTING,
        ERROR
    }

    private final Status status;
    private final String detail;
    private final String transport;
    private final long rxBytes;
    private final long txBytes;
    private final long latestHandshakeEpochMillis;
    private final int recoveryCount;
    private final boolean profileConfigured;

    public TunnelSnapshot(
            final Status status,
            final String detail,
            final String transport,
            final long rxBytes,
            final long txBytes,
            final long latestHandshakeEpochMillis,
            final int recoveryCount,
            final boolean profileConfigured) {
        this.status = Objects.requireNonNull(status);
        this.detail = detail == null ? "" : detail;
        this.transport = transport == null ? "—" : transport;
        this.rxBytes = rxBytes;
        this.txBytes = txBytes;
        this.latestHandshakeEpochMillis = latestHandshakeEpochMillis;
        this.recoveryCount = recoveryCount;
        this.profileConfigured = profileConfigured;
    }

    public static TunnelSnapshot initial(final boolean configured) {
        return new TunnelSnapshot(Status.DISCONNECTED, "Listo", "—", 0, 0, 0, 0, configured);
    }

    public Status status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public String transport() {
        return transport;
    }

    public long rxBytes() {
        return rxBytes;
    }

    public long txBytes() {
        return txBytes;
    }

    public long latestHandshakeEpochMillis() {
        return latestHandshakeEpochMillis;
    }

    public int recoveryCount() {
        return recoveryCount;
    }

    public boolean profileConfigured() {
        return profileConfigured;
    }

    public boolean isActive() {
        return status == Status.CONNECTING || status == Status.CONNECTED
                || status == Status.NO_NETWORK || status == Status.RECOVERING;
    }

    public TunnelSnapshot with(
            final Status newStatus,
            final String newDetail,
            final String newTransport,
            final long newRx,
            final long newTx,
            final long newHandshake,
            final int newRecoveryCount,
            final boolean configured) {
        return new TunnelSnapshot(newStatus, newDetail, newTransport, newRx, newTx,
                newHandshake, newRecoveryCount, configured);
    }
}
