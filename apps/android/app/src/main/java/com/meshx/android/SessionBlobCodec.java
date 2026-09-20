package com.meshx.android;

import okio.ByteString;

/**
 * Framing for the encrypted session blob persisted by
 * {@link EncryptedOriginSessionStore}: {@code base64(iv) "." base64(ciphertext)}.
 *
 * <p>The IV travels in its own base64 segment, so the format is
 * self-describing about the IV length and makes no assumption about the size
 * of the IV the Android Keystore generates. Deliberately free of Android
 * framework classes (okio ships with OkHttp) so the framing is testable on the
 * plain JVM.
 */
final class SessionBlobCodec {
    private SessionBlobCodec() {}

    static String encode(byte[] iv, byte[] ciphertext) {
        if (iv == null || iv.length == 0) {
            throw new IllegalArgumentException("encrypted session blob requires an IV");
        }
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("encrypted session blob requires ciphertext");
        }
        return ByteString.of(iv).base64() + "." + ByteString.of(ciphertext).base64();
    }

    static Blob decode(String value) {
        if (value == null) throw new IllegalArgumentException("invalid encrypted session");
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("invalid encrypted session");
        ByteString iv = ByteString.decodeBase64(parts[0]);
        ByteString ciphertext = ByteString.decodeBase64(parts[1]);
        if (iv == null || ciphertext == null) {
            throw new IllegalArgumentException("invalid encrypted session encoding");
        }
        if (iv.size() == 0) throw new IllegalArgumentException("invalid encrypted session IV");
        if (ciphertext.size() == 0) {
            throw new IllegalArgumentException("invalid encrypted session payload");
        }
        return new Blob(iv.toByteArray(), ciphertext.toByteArray());
    }

    record Blob(byte[] iv, byte[] ciphertext) {}
}
