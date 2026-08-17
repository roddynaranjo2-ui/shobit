package com.nexo.gametunnel.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.nexo.gametunnel.NexoApplication;
import com.nexo.gametunnel.R;
import com.nexo.gametunnel.core.TunnelController;
import com.nexo.gametunnel.model.Profile;
import com.nexo.gametunnel.model.TunnelSnapshot;
import com.nexo.gametunnel.util.InstalledApps;
import com.nexo.gametunnel.util.WireGuardConfigs;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity implements TunnelController.Listener {
    private static final int REQUEST_VPN = 100;
    private static final int REQUEST_CONFIG_FILE = 101;

    private TunnelController controller;
    private String selectedPackage = TunnelController.ARENA_BREAKOUT_PACKAGE;

    private TextView statusTitle;
    private TextView statusDetail;
    private View statusDot;
    private TextView transportValue;
    private TextView handshakeValue;
    private TextView rxValue;
    private TextView txValue;
    private TextView recoveryValue;
    private TextView selectedAppLabel;
    private TextView selectedAppPackage;
    private TextView profileSummary;
    private Button connectButton;
    private Button chooseAppButton;
    private Button importButton;
    private Button pasteButton;
    private Button manualButton;
    private Button deleteProfileButton;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.nexo_background));
        getWindow().setNavigationBarColor(getColor(R.color.nexo_background));
        getWindow().getDecorView().setSystemUiVisibility(0);
        setContentView(R.layout.activity_main);

        controller = ((NexoApplication) getApplication()).tunnelController();
        bindViews();
        loadSelectedPackage();
        refreshAppSelection();
        refreshProfileSummary();

        connectButton.setOnClickListener(v -> toggleTunnel());
        chooseAppButton.setOnClickListener(v -> chooseApplication());
        importButton.setOnClickListener(v -> importConfigFile());
        pasteButton.setOnClickListener(v -> showConfigTextDialog(loadConfigTextOrEmpty()));
        manualButton.setOnClickListener(v -> showManualConfigDialog());
        deleteProfileButton.setOnClickListener(v -> confirmDeleteProfile());
        findViewById(R.id.vpn_settings_button).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
            } catch (final Exception error) {
                Toast.makeText(this, R.string.vpn_settings_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        controller.addListener(this);
    }

    @Override
    protected void onPause() {
        controller.removeListener(this);
        super.onPause();
    }

    private void bindViews() {
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        statusDot = findViewById(R.id.status_dot);
        transportValue = findViewById(R.id.transport_value);
        handshakeValue = findViewById(R.id.handshake_value);
        rxValue = findViewById(R.id.rx_value);
        txValue = findViewById(R.id.tx_value);
        recoveryValue = findViewById(R.id.recovery_value);
        selectedAppLabel = findViewById(R.id.selected_app_label);
        selectedAppPackage = findViewById(R.id.selected_app_package);
        profileSummary = findViewById(R.id.profile_summary);
        connectButton = findViewById(R.id.connect_button);
        chooseAppButton = findViewById(R.id.choose_app_button);
        importButton = findViewById(R.id.import_config_button);
        pasteButton = findViewById(R.id.paste_config_button);
        manualButton = findViewById(R.id.manual_config_button);
        deleteProfileButton = findViewById(R.id.delete_profile_button);
    }

    private void loadSelectedPackage() {
        try {
            final Optional<Profile> profile = controller.loadProfile();
            if (profile.isPresent()) {
                selectedPackage = profile.get().applicationPackage();
            }
        } catch (final Exception error) {
            Toast.makeText(this, R.string.encrypted_profile_error, Toast.LENGTH_LONG).show();
        }
    }

    private void toggleTunnel() {
        final TunnelSnapshot current = controller.snapshot();
        if (current.isActive()) {
            controller.disconnect();
            return;
        }
        if (!current.profileConfigured()) {
            showMessage(getString(R.string.profile_required_title), getString(R.string.profile_required_message));
            return;
        }
        final Intent authorization = VpnService.prepare(this);
        if (authorization == null) {
            controller.connect();
        } else {
            startActivityForResult(authorization, REQUEST_VPN);
        }
    }

    private void chooseApplication() {
        AppPickerDialog.show(this, app -> {
            final String previous = selectedPackage;
            selectedPackage = app.packageName();
            refreshAppSelection();
            try {
                final Optional<Profile> current = controller.loadProfile();
                if (current.isPresent()) {
                    controller.saveProfile(current.get().configText(), selectedPackage);
                    refreshProfileSummary();
                }
            } catch (final Exception error) {
                selectedPackage = previous;
                refreshAppSelection();
                showMessage(getString(R.string.could_not_save), WireGuardConfigs.friendlyError(error));
            }
        });
    }

    private void importConfigFile() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"text/plain", "application/octet-stream", "application/x-wireguard-profile"});
        startActivityForResult(intent, REQUEST_CONFIG_FILE);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                controller.connect();
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_CONFIG_FILE && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            try {
                final String config = readLimitedText(uri);
                showConfigTextDialog(config);
            } catch (final Exception error) {
                showMessage(getString(R.string.import_failed), WireGuardConfigs.friendlyError(error));
            }
        }
    }

    private String readLimitedText(final Uri uri) throws Exception {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                throw new IllegalArgumentException("No se pudo abrir el archivo");
            }
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > WireGuardConfigs.MAX_CONFIG_BYTES) {
                    throw new IllegalArgumentException("El archivo supera 64 KiB");
                }
                bytes.write(buffer, 0, read);
            }
            String result = bytes.toString(StandardCharsets.UTF_8.name());
            if (result.startsWith("\uFEFF")) {
                result = result.substring(1);
            }
            return result;
        }
    }

    private void showConfigTextDialog(final String initialText) {
        final Dialog dialog = new Dialog(this, R.style.NexoDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_config_text);
        final EditText editor = dialog.findViewById(R.id.config_text);
        editor.setText(initialText);
        dialog.findViewById(R.id.cancel_config).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.save_config).setOnClickListener(v -> {
            try {
                controller.saveProfile(editor.getText().toString(), selectedPackage);
                refreshProfileSummary();
                Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (final Exception error) {
                ((TextView) dialog.findViewById(R.id.config_error))
                        .setText(WireGuardConfigs.friendlyError(error));
                dialog.findViewById(R.id.config_error).setVisibility(View.VISIBLE);
            }
        });
        dialog.show();
        secureAndSize(dialog, 0.95f, 0.86f);
    }

    private void showManualConfigDialog() {
        final Dialog dialog = new Dialog(this, R.style.NexoDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_manual_config);
        dialog.findViewById(R.id.cancel_manual).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.save_manual).setOnClickListener(v -> {
            try {
                final String config = WireGuardConfigs.createManual(
                        text(dialog, R.id.private_key),
                        text(dialog, R.id.client_address),
                        text(dialog, R.id.dns_servers),
                        text(dialog, R.id.peer_public_key),
                        text(dialog, R.id.preshared_key),
                        text(dialog, R.id.endpoint_host),
                        text(dialog, R.id.endpoint_port),
                        text(dialog, R.id.allowed_ips),
                        text(dialog, R.id.keepalive),
                        text(dialog, R.id.mtu));
                controller.saveProfile(config, selectedPackage);
                refreshProfileSummary();
                Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (final Exception error) {
                final TextView errorView = dialog.findViewById(R.id.manual_error);
                errorView.setText(WireGuardConfigs.friendlyError(error));
                errorView.setVisibility(View.VISIBLE);
            }
        });
        dialog.show();
        secureAndSize(dialog, 0.95f, 0.90f);
    }

    private String text(final Dialog dialog, final int id) {
        return ((EditText) dialog.findViewById(id)).getText().toString();
    }

    private void secureAndSize(final Dialog dialog, final float width, final float height) {
        final Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        window.setLayout(
                (int) (getResources().getDisplayMetrics().widthPixels * width),
                (int) (getResources().getDisplayMetrics().heightPixels * height));
    }

    private void confirmDeleteProfile() {
        new AlertDialog.Builder(this, R.style.NexoAlertDialog)
                .setTitle(R.string.delete_profile_title)
                .setMessage(R.string.delete_profile_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    try {
                        controller.deleteProfile();
                        selectedPackage = TunnelController.ARENA_BREAKOUT_PACKAGE;
                        refreshAppSelection();
                        refreshProfileSummary();
                    } catch (final Exception error) {
                        showMessage(getString(R.string.could_not_delete), error.getMessage());
                    }
                })
                .show();
    }

    private String loadConfigTextOrEmpty() {
        try {
            return controller.loadProfile().map(Profile::configText).orElse("");
        } catch (final Exception error) {
            Toast.makeText(this, R.string.encrypted_profile_error, Toast.LENGTH_LONG).show();
            return "";
        }
    }

    private void refreshAppSelection() {
        selectedAppLabel.setText(InstalledApps.labelFor(this, selectedPackage));
        selectedAppPackage.setText(selectedPackage);
    }

    private void refreshProfileSummary() {
        try {
            final Optional<Profile> profile = controller.loadProfile();
            if (profile.isEmpty()) {
                profileSummary.setText(R.string.no_profile);
                deleteProfileButton.setVisibility(View.GONE);
                return;
            }
            selectedPackage = profile.get().applicationPackage();
            refreshAppSelection();
            profileSummary.setText(WireGuardConfigs.summarize(profile.get().configText()).displayText());
            deleteProfileButton.setVisibility(View.VISIBLE);
        } catch (final Exception error) {
            profileSummary.setText(R.string.encrypted_profile_error);
            deleteProfileButton.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onTunnelSnapshot(final TunnelSnapshot value) {
        final int color;
        final String title;
        switch (value.status()) {
            case CONNECTED:
                color = getColor(R.color.nexo_green);
                title = getString(R.string.connected);
                break;
            case CONNECTING:
                color = getColor(R.color.nexo_amber);
                title = getString(R.string.connecting);
                break;
            case NO_NETWORK:
                color = getColor(R.color.nexo_amber);
                title = getString(R.string.no_network);
                break;
            case RECOVERING:
                color = getColor(R.color.nexo_amber);
                title = getString(R.string.recovering);
                break;
            case ERROR:
                color = getColor(R.color.nexo_red);
                title = getString(R.string.error);
                break;
            case DISCONNECTING:
                color = getColor(R.color.nexo_muted);
                title = getString(R.string.disconnecting);
                break;
            case DISCONNECTED:
            default:
                color = getColor(R.color.nexo_muted);
                title = getString(R.string.disconnected);
                break;
        }
        statusTitle.setText(title);
        statusDetail.setText(value.detail());
        final GradientDrawable dot = (GradientDrawable) statusDot.getBackground().mutate();
        dot.setColor(color);
        statusDot.setBackground(dot);

        transportValue.setText(value.transport());
        handshakeValue.setText(formatHandshake(value.latestHandshakeEpochMillis()));
        rxValue.setText(formatBytes(value.rxBytes()));
        txValue.setText(formatBytes(value.txBytes()));
        recoveryValue.setText(value.recoveryCount() == 0
                ? getString(R.string.no_restarts)
                : getResources().getQuantityString(R.plurals.recovery_count,
                        value.recoveryCount(), value.recoveryCount()));

        final boolean transitional = value.status() == TunnelSnapshot.Status.CONNECTING
                || value.status() == TunnelSnapshot.Status.DISCONNECTING
                || value.status() == TunnelSnapshot.Status.RECOVERING;
        connectButton.setEnabled(!transitional);
        if (value.isActive()) {
            connectButton.setText(R.string.disconnect);
            connectButton.setTextColor(getColor(R.color.nexo_green));
            connectButton.setBackgroundResource(R.drawable.bg_connect_active);
        } else {
            connectButton.setText(value.status() == TunnelSnapshot.Status.ERROR
                    ? R.string.retry : R.string.connect);
            connectButton.setTextColor(getColor(R.color.nexo_ink));
            connectButton.setBackgroundResource(R.drawable.bg_connect_button);
        }

        final boolean settingsEnabled = !value.isActive()
                && value.status() != TunnelSnapshot.Status.DISCONNECTING;
        chooseAppButton.setEnabled(settingsEnabled);
        importButton.setEnabled(settingsEnabled);
        pasteButton.setEnabled(settingsEnabled);
        manualButton.setEnabled(settingsEnabled);
        deleteProfileButton.setEnabled(settingsEnabled);
    }

    private String formatHandshake(final long epochMillis) {
        if (epochMillis <= 0) {
            return getString(R.string.never);
        }
        final long age = Math.max(0, System.currentTimeMillis() - epochMillis);
        if (age < 5_000) {
            return getString(R.string.just_now);
        }
        if (age < 60_000) {
            return getString(R.string.seconds_ago, TimeUnit.MILLISECONDS.toSeconds(age));
        }
        if (age < 3_600_000) {
            return getString(R.string.minutes_ago, TimeUnit.MILLISECONDS.toMinutes(age));
        }
        return getString(R.string.hours_ago, TimeUnit.MILLISECONDS.toHours(age));
    }

    private String formatBytes(final long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        final String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.getDefault(), value >= 100 ? "%.0f %s" : "%.1f %s",
                value, units[unit]);
    }

    private void showMessage(final String title, final String message) {
        new AlertDialog.Builder(this, R.style.NexoAlertDialog)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.understood, null)
                .show();
    }
}
