package com.dsacms.sheikhrequests;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the short-lived API session encrypted with an Android Keystore AES key. */
public final class SecureSessionStore {
    private static final String PREFS = "sheikh_session";
    private static final String TOKEN = "session_token";
    private static final String KEY_ALIAS = "dsacms_sheikh_session_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final SharedPreferences preferences;

    public SecureSessionStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void save(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        if (!preferences.edit().putString(TOKEN, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            throw new IllegalStateException("Unable to store the session securely");
        }
    }

    public synchronized String read() throws Exception {
        String encoded = preferences.getString(TOKEN, null);
        if (encoded == null) return null;
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        int ivLength = 12;
        if (packed.length <= ivLength) throw new IllegalStateException("Invalid saved session");
        byte[] iv = Arrays.copyOfRange(packed, 0, ivLength);
        byte[] encrypted = Arrays.copyOfRange(packed, ivLength, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    public synchronized void clear() {
        preferences.edit().remove(TOKEN).apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
