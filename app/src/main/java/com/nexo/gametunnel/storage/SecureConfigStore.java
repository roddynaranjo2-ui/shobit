package com.nexo.gametunnel.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.nexo.gametunnel.model.Profile;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the WireGuard profile with AES-256-GCM under a non-exportable Android Keystore key.
 * Backups are also disabled in the application manifest so private keys never leave the device.
 */
public final class SecureConfigStore {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "nexo_profile_aes_v1";
    private static final String PREFS = "nexo_secure_profile";
    private static final String CONFIG = "config";
    private static final String PACKAGE = "package";
    private static final String SEPARATOR = ".";

    private final SharedPreferences preferences;

    public SecureConfigStore(final Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(final Profile profile) throws GeneralSecurityException {
        final String encryptedConfig = encrypt(profile.configText());
        final String encryptedPackage = encrypt(profile.applicationPackage());
        if (!preferences.edit()
                .putString(CONFIG, encryptedConfig)
                .putString(PACKAGE, encryptedPackage)
                .commit()) {
            throw new GeneralSecurityException("No se pudo guardar el perfil de forma segura");
        }
    }

    public synchronized Optional<Profile> load() throws GeneralSecurityException {
        final String config = preferences.getString(CONFIG, null);
        final String applicationPackage = preferences.getString(PACKAGE, null);
        if (config == null || applicationPackage == null) {
            return Optional.empty();
        }
        return Optional.of(new Profile(decrypt(config), decrypt(applicationPackage)));
    }

    public synchronized boolean hasProfile() {
        return preferences.contains(CONFIG) && preferences.contains(PACKAGE);
    }

    public synchronized void clear() {
        preferences.edit().clear().commit();
    }

    private String encrypt(final String plainText) throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        final byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + SEPARATOR
                + Base64.encodeToString(cipherText, Base64.NO_WRAP);
    }

    private String decrypt(final String encoded) throws GeneralSecurityException {
        final String[] parts = encoded.split("\\.", 2);
        if (parts.length != 2) {
            throw new GeneralSecurityException("Perfil cifrado dañado");
        }
        final byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        final byte[] cipherText = Base64.decode(parts[1], Base64.NO_WRAP);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        final KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        final java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }

        final KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}
