package com.nexo.gametunnel.core;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.nexo.gametunnel.model.Profile;
import com.nexo.gametunnel.model.TunnelSnapshot;
import com.nexo.gametunnel.storage.SecureConfigStore;
import com.nexo.gametunnel.util.WireGuardConfigs;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the one WireGuard userspace backend and its adaptive recovery policy.
 *
 * There are deliberately no continuous pings. Health is inferred locally from WireGuard counters,
 * handshake timestamps and Android's default-network callbacks. Natural WireGuard roaming always
 * gets a grace window before a restart is considered.
 */
public final class TunnelController {
    public static final String ARENA_BREAKOUT_PACKAGE = "com.proximabeta.mf.uamo";

    private static final long STATS_PERIOD_SECONDS = 2;
    private static final long ROAMING_GRACE_MILLIS = 12_000;
    private static final long STALL_WINDOW_MILLIS = 18_000;
    private static final long RESTART_COOLDOWN_MILLIS = 60_000;
    private static final long RECOVERY_TX_THRESHOLD = 512;
    private static final long STALL_TX_THRESHOLD = 4_096;

    public interface Listener {
        void onTunnelSnapshot(TunnelSnapshot snapshot);
    }

    private final Application application;
    private final SecureConfigStore secureStore;
    private final GoBackend backend;
    private final NexoTunnel tunnel = new NexoTunnel();
    private final ConnectivityManager connectivityManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread thread = new Thread(r, "NexoTunnelWorker");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private volatile TunnelSnapshot snapshot;
    private volatile Config activeConfig;
    private ScheduledFuture<?> statisticsTask;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long networkMonitorGeneration;
    private Network currentNetwork;
    private boolean seenInitialNetwork;
    private boolean recoveryWindow;
    private long recoveryStartedElapsed;
    private long recoveryBaselineRx;
    private long recoveryBaselineTx;
    private long recoveryBaselineHandshake;
    private long rxOffset;
    private long txOffset;
    private long lastRawRx;
    private long lastRawTx;
    private long lastRx;
    private long lastTx;
    private long latestHandshake;
    private long noRxStartedElapsed = -1;
    private long noRxStartedTx;
    private long lastTxActivityElapsed;
    private long lastRestartElapsed = -RESTART_COOLDOWN_MILLIS;
    private int recoveryCount;
    private boolean requestedDisconnect;

    public TunnelController(final Application application) {
        this.application = application;
        secureStore = new SecureConfigStore(application);
        connectivityManager = (ConnectivityManager) application.getSystemService(Context.CONNECTIVITY_SERVICE);
        snapshot = TunnelSnapshot.initial(secureStore.hasProfile());
        backend = new GoBackend(application);
        GoBackend.setAlwaysOnCallback(() -> worker.execute(() -> connectInternal(true)));
    }

    public TunnelSnapshot snapshot() {
        return snapshot;
    }

    public void addListener(final Listener listener) {
        listeners.add(listener);
        mainHandler.post(() -> {
            if (listeners.contains(listener)) {
                listener.onTunnelSnapshot(snapshot);
            }
        });
    }

    public void removeListener(final Listener listener) {
        listeners.remove(listener);
    }

    public Profile saveProfile(final String configText, final String applicationPackage)
            throws Exception {
        if (snapshot.isActive()) {
            throw new IllegalStateException("Desconecta el túnel antes de cambiar el perfil");
        }
        ensureApplicationInstalled(applicationPackage);
        final String normalized = WireGuardConfigs.normalizeForStorage(configText);
        final Profile profile = new Profile(normalized, applicationPackage);
        secureStore.save(profile);
        publish(snapshot.with(snapshot.status(), "Perfil guardado", snapshot.transport(),
                snapshot.rxBytes(), snapshot.txBytes(), snapshot.latestHandshakeEpochMillis(),
                snapshot.recoveryCount(), true));
        return profile;
    }

    public Optional<Profile> loadProfile() throws GeneralSecurityException {
        return secureStore.load();
    }

    public void deleteProfile() {
        if (snapshot.isActive()) {
            throw new IllegalStateException("Desconecta el túnel antes de borrar el perfil");
        }
        secureStore.clear();
        publish(TunnelSnapshot.initial(false));
    }

    public void connect() {
        worker.execute(() -> connectInternal(false));
    }

    public void disconnect() {
        worker.execute(this::disconnectInternal);
    }

    private void connectInternal(final boolean alwaysOn) {
        if (snapshot.isActive() && snapshot.status() != TunnelSnapshot.Status.ERROR) {
            return;
        }
        requestedDisconnect = false;
        update(TunnelSnapshot.Status.CONNECTING,
                alwaysOn ? "Restaurando VPN siempre activa…" : "Preparando túnel…");
        try {
            final Profile profile = secureStore.load()
                    .orElseThrow(() -> new IllegalStateException("Importa o crea un perfil WireGuard primero"));
            ensureApplicationInstalled(profile.applicationPackage());
            activeConfig = WireGuardConfigs.forApplication(
                    profile.configText(), profile.applicationPackage());

            resetSessionCounters();
            registerNetworkMonitor();
            backend.setState(tunnel, Tunnel.State.UP, activeConfig);
            update(TunnelSnapshot.Status.CONNECTED, "Túnel activo");
            startStatistics();
        } catch (final Exception error) {
            stopStatistics();
            unregisterNetworkMonitor();
            // GoBackend can fail after creating the TUN but before setState() returns. A best-effort
            // teardown prevents that partially started VPN from surviving behind an error state.
            if (activeConfig != null) {
                try {
                    backend.setState(tunnel, Tunnel.State.DOWN, null);
                } catch (final Exception ignored) {
                    // Preserve the original, more useful connection error below.
                }
            }
            activeConfig = null;
            update(TunnelSnapshot.Status.ERROR, friendlyConnectionError(error));
        }
    }

    private void disconnectInternal() {
        if (!snapshot.isActive() && snapshot.status() != TunnelSnapshot.Status.ERROR) {
            return;
        }
        requestedDisconnect = true;
        update(TunnelSnapshot.Status.DISCONNECTING, "Cerrando túnel…");
        stopStatistics();
        unregisterNetworkMonitor();
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null);
            activeConfig = null;
            update(TunnelSnapshot.Status.DISCONNECTED, "Desconectado");
        } catch (final Exception error) {
            update(TunnelSnapshot.Status.ERROR, friendlyConnectionError(error));
        } finally {
            requestedDisconnect = false;
        }
    }

    private void startStatistics() {
        stopStatistics();
        statisticsTask = worker.scheduleWithFixedDelay(
                this::readStatisticsSafely,
                0,
                STATS_PERIOD_SECONDS,
                TimeUnit.SECONDS);
    }

    private void stopStatistics() {
        if (statisticsTask != null) {
            statisticsTask.cancel(false);
            statisticsTask = null;
        }
    }

    private void readStatisticsSafely() {
        if (!snapshot.isActive() || activeConfig == null) {
            return;
        }
        try {
            if (backend.getState(tunnel) != Tunnel.State.UP) {
                if (!requestedDisconnect) {
                    stopStatistics();
                    unregisterNetworkMonitor();
                    activeConfig = null;
                    update(TunnelSnapshot.Status.ERROR, "Android detuvo el servicio VPN");
                }
                return;
            }

            final Statistics statistics = backend.getStatistics(tunnel);
            final long rawRx = statistics.totalRx();
            final long rawTx = statistics.totalTx();
            final long sessionRx = rxOffset + rawRx;
            final long sessionTx = txOffset + rawTx;
            long handshake = 0;
            for (final com.wireguard.crypto.Key peer : statistics.peers()) {
                final Statistics.PeerStats peerStats = statistics.peer(peer);
                if (peerStats != null) {
                    handshake = Math.max(handshake, peerStats.latestHandshakeEpochMillis());
                }
            }

            // Recovery rebuilds the backend and resets its raw counters. Abort this iteration when
            // that happens so pre-restart values can never overwrite the new session baseline.
            if (evaluateHealth(sessionRx, sessionTx, handshake, rawRx, rawTx)) {
                return;
            }
            lastRawRx = rawRx;
            lastRawTx = rawTx;
            lastRx = sessionRx;
            lastTx = sessionTx;
            latestHandshake = handshake;

            final TunnelSnapshot current = snapshot;
            publish(current.with(current.status(), current.detail(), current.transport(),
                    sessionRx, sessionTx, handshake, recoveryCount, secureStore.hasProfile()));
        } catch (final Exception error) {
            // A single failed local stats read must not tear down an otherwise working game tunnel.
            final TunnelSnapshot current = snapshot;
            publish(current.with(current.status(), "Diagnóstico temporalmente no disponible",
                    current.transport(), current.rxBytes(), current.txBytes(),
                    current.latestHandshakeEpochMillis(), recoveryCount, secureStore.hasProfile()));
        }
    }

    /**
     * @return true when this check rebuilt (or attempted to rebuild) the backend. The caller must
     *         then discard the statistics sampled before that rebuild.
     */
    private boolean evaluateHealth(
            final long rx,
            final long tx,
            final long handshake,
            final long rawRx,
            final long rawTx) {
        final long now = SystemClock.elapsedRealtime();
        if (tx > lastTx) {
            lastTxActivityElapsed = now;
        }
        if (rx > lastRx) {
            noRxStartedElapsed = -1;
            recoveryWindow = false;
        } else if (tx > lastTx) {
            if (noRxStartedElapsed < 0) {
                noRxStartedElapsed = now;
                noRxStartedTx = tx;
            }
        }

        if (recoveryWindow) {
            if (rx > recoveryBaselineRx || handshake > recoveryBaselineHandshake) {
                recoveryWindow = false;
                update(TunnelSnapshot.Status.CONNECTED, "Roaming recuperado sin reinicio");
            } else if (now - recoveryStartedElapsed >= ROAMING_GRACE_MILLIS
                    && tx - recoveryBaselineTx >= RECOVERY_TX_THRESHOLD
                    && now - lastTxActivityElapsed < 6_000
                    && forceRecovery("El tráfico no volvió después del cambio de red", rawRx, rawTx)) {
                return true;
            }
        }

        final boolean sustainedOneWayTraffic = noRxStartedElapsed >= 0
                && now - noRxStartedElapsed >= STALL_WINDOW_MILLIS
                && tx - noRxStartedTx >= STALL_TX_THRESHOLD
                && now - lastTxActivityElapsed < 6_000;
        return sustainedOneWayTraffic
                && forceRecovery("Posible microcorte: solo salía tráfico", rawRx, rawTx);
    }

    /** @return true if a recovery was attempted, even when bringing the tunnel back up failed. */
    private boolean forceRecovery(final String reason, final long currentRawRx, final long currentRawTx) {
        final long now = SystemClock.elapsedRealtime();
        if (activeConfig == null || currentNetwork == null
                || now - lastRestartElapsed < RESTART_COOLDOWN_MILLIS) {
            return false;
        }
        lastRestartElapsed = now;
        recoveryCount++;
        update(TunnelSnapshot.Status.RECOVERING, reason);
        try {
            // Preserve every byte in the sample that triggered recovery before GoBackend resets.
            rxOffset += currentRawRx;
            txOffset += currentRawTx;
            backend.setState(tunnel, Tunnel.State.DOWN, null);
            Thread.sleep(250);
            backend.setState(tunnel, Tunnel.State.UP, activeConfig);
            lastRawRx = 0;
            lastRawTx = 0;
            lastRx = rxOffset;
            lastTx = txOffset;
            noRxStartedElapsed = -1;
            recoveryWindow = false;
            update(TunnelSnapshot.Status.CONNECTED, "Túnel recuperado");
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            stopStatistics();
            unregisterNetworkMonitor();
            activeConfig = null;
            update(TunnelSnapshot.Status.ERROR, "Recuperación interrumpida");
        } catch (final Exception error) {
            stopStatistics();
            unregisterNetworkMonitor();
            activeConfig = null;
            update(TunnelSnapshot.Status.ERROR, "No se pudo recuperar: "
                    + friendlyConnectionError(error));
        }
        return true;
    }

    private void registerNetworkMonitor() {
        unregisterNetworkMonitor();
        seenInitialNetwork = false;
        currentNetwork = null;
        final long generation = ++networkMonitorGeneration;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(final Network network) {
                worker.execute(() -> handleNetworkAvailable(network, generation));
            }

            @Override
            public void onCapabilitiesChanged(
                    final Network network,
                    final NetworkCapabilities capabilities) {
                worker.execute(() -> {
                    if (isCurrentNetworkMonitor(generation) && network.equals(currentNetwork)) {
                        updateTransport(capabilities);
                    }
                });
            }

            @Override
            public void onLost(final Network network) {
                worker.execute(() -> handleNetworkLost(network, generation));
            }
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
        worker.schedule(() -> {
            if (isCurrentNetworkMonitor(generation)
                    && currentNetwork == null && snapshot.isActive()) {
                update(TunnelSnapshot.Status.NO_NETWORK, "Sin red subyacente; esperando a ETECSA…");
            }
        }, 1500, TimeUnit.MILLISECONDS);
    }

    private void unregisterNetworkMonitor() {
        // Invalidate queued callbacks before unregistering. Android may deliver callbacks that were
        // already in flight, so checking only networkCallback != null is not sufficient.
        networkMonitorGeneration++;
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (final IllegalArgumentException ignored) {
                // Already unregistered by Android.
            }
            networkCallback = null;
        }
        currentNetwork = null;
        seenInitialNetwork = false;
        recoveryWindow = false;
    }

    private boolean isCurrentNetworkMonitor(final long generation) {
        return networkCallback != null && networkMonitorGeneration == generation;
    }

    private void handleNetworkAvailable(final Network network, final long generation) {
        if (!isCurrentNetworkMonitor(generation)) {
            return;
        }
        final boolean isInitial = !seenInitialNetwork;
        final boolean networkChanged = seenInitialNetwork && !network.equals(currentNetwork);
        seenInitialNetwork = true;
        currentNetwork = network;
        updateTransport(connectivityManager.getNetworkCapabilities(network));

        if (!isInitial && (networkChanged || snapshot.status() == TunnelSnapshot.Status.NO_NETWORK)) {
            recoveryWindow = true;
            recoveryStartedElapsed = SystemClock.elapsedRealtime();
            recoveryBaselineRx = lastRx;
            recoveryBaselineTx = lastTx;
            recoveryBaselineHandshake = latestHandshake;
            update(TunnelSnapshot.Status.CONNECTED, "Red disponible; WireGuard está haciendo roaming…");
        } else if (snapshot.status() == TunnelSnapshot.Status.NO_NETWORK) {
            update(TunnelSnapshot.Status.CONNECTED, "Red disponible");
        }
    }

    private void handleNetworkLost(final Network network, final long generation) {
        if (!isCurrentNetworkMonitor(generation) || !network.equals(currentNetwork)) {
            return;
        }
        currentNetwork = null;
        worker.schedule(() -> {
            if (isCurrentNetworkMonitor(generation)
                    && currentNetwork == null && snapshot.isActive()) {
                recoveryWindow = false;
                update(TunnelSnapshot.Status.NO_NETWORK, "Sin red; el túnel queda listo para roaming");
            }
        }, 800, TimeUnit.MILLISECONDS);
    }

    private void updateTransport(final NetworkCapabilities capabilities) {
        String transport = "Otra red";
        if (capabilities == null) {
            transport = "Sin red";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transport = "Datos móviles";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transport = "Wi‑Fi";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transport = "Ethernet";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            transport = "Bluetooth";
        }
        final TunnelSnapshot current = snapshot;
        publish(current.with(current.status(), current.detail(), transport,
                current.rxBytes(), current.txBytes(), current.latestHandshakeEpochMillis(),
                recoveryCount, secureStore.hasProfile()));
    }

    private void resetSessionCounters() {
        rxOffset = 0;
        txOffset = 0;
        lastRawRx = 0;
        lastRawTx = 0;
        lastRx = 0;
        lastTx = 0;
        latestHandshake = 0;
        noRxStartedElapsed = -1;
        lastTxActivityElapsed = 0;
        recoveryWindow = false;
        recoveryCount = 0;
        lastRestartElapsed = -RESTART_COOLDOWN_MILLIS;
    }

    private void ensureApplicationInstalled(final String packageName) throws PackageManager.NameNotFoundException {
        if (!WireGuardConfigs.isPackageName(packageName)) {
            throw new IllegalArgumentException("Selecciona una aplicación válida");
        }
        application.getPackageManager().getApplicationInfo(packageName, 0);
    }

    private void update(final TunnelSnapshot.Status status, final String detail) {
        final TunnelSnapshot current = snapshot;
        publish(current.with(status, detail, current.transport(), current.rxBytes(),
                current.txBytes(), current.latestHandshakeEpochMillis(), recoveryCount,
                secureStore.hasProfile()));
    }

    private void publish(final TunnelSnapshot value) {
        snapshot = value;
        for (final Listener listener : listeners) {
            mainHandler.post(() -> {
                if (listeners.contains(listener)) {
                    listener.onTunnelSnapshot(value);
                }
            });
        }
    }

    private String friendlyConnectionError(final Throwable error) {
        if (error instanceof PackageManager.NameNotFoundException) {
            return "La aplicación seleccionada no está instalada";
        }
        if (error instanceof GeneralSecurityException) {
            return "No se pudo abrir el perfil cifrado. Vuelve a importarlo";
        }
        if (error instanceof IOException) {
            return "No se pudo leer el perfil WireGuard";
        }
        final String message = WireGuardConfigs.friendlyError(error);
        if (message.contains("VPN_NOT_AUTHORIZED")) {
            return "Android no autorizó la conexión VPN";
        }
        if (message.contains("DNS_RESOLUTION_FAILURE")) {
            return "No se pudo resolver el dominio del VPS";
        }
        return message;
    }

    private final class NexoTunnel implements Tunnel {
        @Override
        public String getName() {
            return "nexo";
        }

        @Override
        public void onStateChange(final State newState) {
            // Calls caused by our worker are already represented with richer transitional states.
            // A callback from Android's service thread indicates an external teardown.
            if (newState == State.DOWN && !Thread.currentThread().getName().equals("NexoTunnelWorker")) {
                worker.execute(() -> {
                    if (snapshot.isActive() && !requestedDisconnect) {
                        stopStatistics();
                        unregisterNetworkMonitor();
                        activeConfig = null;
                        update(TunnelSnapshot.Status.ERROR, "Android detuvo el servicio VPN");
                    }
                });
            }
        }
    }
}
