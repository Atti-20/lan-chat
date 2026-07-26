package com.atti20.lanchat;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.json.JSONArray;

/**
 * App-private cookie storage encrypted by a non-exportable Android Keystore
 * key. The canonical origin is authenticated as AES-GCM associated data, so a
 * blob copied between node keys cannot be decrypted for another node.
 */
final class EncryptedOriginSessionStore implements OriginSessionStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "meshx_auth_cookie_key_v1";
    private static final String PREFERENCES = "meshx_native_auth_v1";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;

    private final SharedPreferences preferences;
    private final SecureRandom secureRandom = new SecureRandom();

    EncryptedOriginSessionStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @Override
    public synchronized List<Cookie> load(String origin) {
        String encrypted = preferences.getString(preferenceKey(origin), null);
        if (encrypted == null || encrypted.isBlank()) return List.of();
        try {
            String serialized = new String(decrypt(origin, encrypted), StandardCharsets.UTF_8);
            JSONArray values = new JSONArray(serialized);
            HttpUrl root = requireOriginUrl(origin);
            List<Cookie> cookies = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int index = 0; index < values.length(); index++) {
                Cookie cookie = Cookie.parse(root, values.getString(index));
                if (cookie != null && cookie.expiresAt() > now && cookie.persistent()) {
                    cookies.add(cookie);
                }
            }
            if (cookies.isEmpty()) remove(origin);
            return List.copyOf(cookies);
        } catch (Exception exception) {
            // A missing/invalidated key or a modified blob must fail closed and
            // require a new login instead of exposing or reusing stale state.
            remove(origin);
            return List.of();
        }
    }

    @Override
    public synchronized void save(String origin, List<Cookie> cookies) {
        try {
            JSONArray values = new JSONArray();
            long now = System.currentTimeMillis();
            for (Cookie cookie : cookies) {
                if (cookie.persistent() && cookie.expiresAt() > now) values.put(cookie.toString());
            }
            if (values.length() == 0) {
                remove(origin);
                return;
            }
            String encrypted = encrypt(origin, values.toString().getBytes(StandardCharsets.UTF_8));
            if (!preferences.edit().putString(preferenceKey(origin), encrypted).commit()) {
                throw new SessionStoreException("unable to persist native authentication session");
            }
        } catch (SessionStoreException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SessionStoreException("unable to persist native authentication session", exception);
        }
    }

    @Override
    public synchronized void remove(String origin) {
        if (!preferences.edit().remove(preferenceKey(origin)).commit()) {
            throw new SessionStoreException("unable to clear native authentication session");
        }
    }

    private String encrypt(String origin, byte[] plaintext) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(origin.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext);
        return Base64.encodeToString(iv, Base64.NO_WRAP)
                + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private byte[] decrypt(String origin, String value) throws Exception {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("invalid encrypted session");
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        if (iv.length != IV_BYTES) throw new IllegalArgumentException("invalid encrypted session IV");
        byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(origin.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(encrypted);
    }

    private SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry secretKeyEntry) {
            return secretKeyEntry.getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String preferenceKey(String origin) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(origin.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("origin_");
            for (byte value : digest) key.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return key.toString();
        } catch (Exception exception) {
            throw new SessionStoreException("unable to identify native authentication session", exception);
        }
    }

    private static HttpUrl requireOriginUrl(String origin) {
        HttpUrl url = HttpUrl.parse(origin + "/");
        if (url == null) throw new SessionStoreException("invalid native authentication origin");
        return url;
    }

    static final class SessionStoreException extends RuntimeException {
        SessionStoreException(String message) {
            super(message);
        }

        SessionStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
