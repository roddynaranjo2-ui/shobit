package com.nexo.gametunnel.util;

import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;
import com.wireguard.config.Interface;
import com.wireguard.config.Peer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/** Parsing, sanitizing and per-application adaptation for wg-quick profiles. */
public final class WireGuardConfigs {
    public static final int MAX_CONFIG_BYTES = 64 * 1024;

    private WireGuardConfigs() {
    }

    public static Config parse(final String text) throws IOException, BadConfigException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("La configuración está vacía");
        }
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("La configuración supera 64 KiB");
        }
        final Config config = Config.parse(new ByteArrayInputStream(bytes));
        if (config.getPeers().isEmpty()) {
            throw new IllegalArgumentException("La configuración necesita al menos un [Peer]");
        }
        return config;
    }

    /** Removes app directives from imported files: the app selector is the single source of truth. */
    public static String normalizeForStorage(final String text) throws IOException, BadConfigException {
        final Config parsed = parse(text);
        final Interface cleanInterface = copyInterface(parsed.getInterface(), null);
        return new Config.Builder()
                .setInterface(cleanInterface)
                .addPeers(parsed.getPeers())
                .build()
                .toWgQuickString();
    }

    /** Returns a new immutable config restricted exclusively to the selected application. */
    public static Config forApplication(final String storedText, final String applicationPackage)
            throws IOException, BadConfigException {
        if (!isPackageName(applicationPackage)) {
            throw new IllegalArgumentException("El identificador de la aplicación no es válido");
        }
        final Config parsed = parse(storedText);
        return new Config.Builder()
                .setInterface(copyInterface(parsed.getInterface(), applicationPackage))
                .addPeers(parsed.getPeers())
                .build();
    }

    private static Interface copyInterface(
            final Interface source,
            final String onlyApplication) throws BadConfigException {
        final Interface.Builder builder = new Interface.Builder()
                .addAddresses(source.getAddresses())
                .addDnsServers(source.getDnsServers())
                .addDnsSearchDomains(source.getDnsSearchDomains())
                .setKeyPair(source.getKeyPair());

        source.getListenPort().ifPresent(port -> {
            try {
                builder.setListenPort(port);
            } catch (final BadConfigException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
        source.getMtu().ifPresent(mtu -> {
            try {
                builder.setMtu(mtu);
            } catch (final BadConfigException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
        if (onlyApplication != null) {
            builder.includeApplication(onlyApplication);
        }
        return builder.build();
    }

    public static String createManual(
            final String privateKey,
            final String clientAddress,
            final String dns,
            final String peerPublicKey,
            final String presharedKey,
            final String endpointHost,
            final String endpointPort,
            final String allowedIps,
            final String keepalive,
            final String mtu) throws IOException, BadConfigException {
        final String host = require(endpointHost, "Falta el host o IP del VPS");
        final String port = require(endpointPort, "Falta el puerto del VPS");
        final String endpoint = host.contains(":") && !host.startsWith("[")
                ? "[" + host + "]:" + port
                : host + ":" + port;

        final StringBuilder text = new StringBuilder();
        text.append("[Interface]\n")
                .append("PrivateKey = ").append(require(privateKey, "Falta la clave privada del cliente")).append('\n')
                .append("Address = ").append(require(clientAddress, "Falta la dirección del cliente")).append('\n');
        if (!dns.isBlank()) {
            text.append("DNS = ").append(dns.trim()).append('\n');
        }
        if (!mtu.isBlank()) {
            text.append("MTU = ").append(mtu.trim()).append('\n');
        }
        text.append("\n[Peer]\n")
                .append("PublicKey = ").append(require(peerPublicKey, "Falta la clave pública del servidor")).append('\n');
        if (!presharedKey.isBlank()) {
            text.append("PresharedKey = ").append(presharedKey.trim()).append('\n');
        }
        text.append("Endpoint = ").append(endpoint).append('\n')
                .append("AllowedIPs = ").append(allowedIps.isBlank() ? "0.0.0.0/0, ::/0" : allowedIps.trim()).append('\n')
                .append("PersistentKeepalive = ").append(keepalive.isBlank() ? "25" : keepalive.trim()).append('\n');
        return normalizeForStorage(text.toString());
    }

    public static ProfileSummary summarize(final String storedText) throws IOException, BadConfigException {
        final Config config = parse(storedText);
        final String addresses = config.getInterface().getAddresses().stream()
                .map(Object::toString)
                .collect(Collectors.joining(", "));
        final String endpoints = config.getPeers().stream()
                .map(Peer::getEndpoint)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .map(Object::toString)
                .collect(Collectors.joining(", "));
        return new ProfileSummary(
                addresses.isBlank() ? "Sin dirección" : addresses,
                endpoints.isBlank() ? "Sin endpoint" : endpoints,
                config.getPeers().size());
    }

    public static boolean isPackageName(final String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    private static String require(final String value, final String message) {
        Objects.requireNonNull(value);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    public static String friendlyError(final Throwable error) {
        final String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "Configuración WireGuard inválida";
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    public static final class ProfileSummary {
        private final String addresses;
        private final String endpoints;
        private final int peerCount;

        public ProfileSummary(final String addresses, final String endpoints, final int peerCount) {
            this.addresses = addresses;
            this.endpoints = endpoints;
            this.peerCount = peerCount;
        }

        public String addresses() {
            return addresses;
        }

        public String endpoints() {
            return endpoints;
        }

        public int peerCount() {
            return peerCount;
        }

        public String displayText() {
            return String.format(Locale.ROOT, "%s  •  %s  •  %d peer%s",
                    endpoints, addresses, peerCount, peerCount == 1 ? "" : "s");
        }
    }
}
