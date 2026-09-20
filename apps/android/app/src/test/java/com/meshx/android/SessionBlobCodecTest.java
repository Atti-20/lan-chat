package com.meshx.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SessionBlobCodecTest {

    @Test
    public void roundTripsIvAndCiphertext() {
        byte[] iv = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        byte[] ciphertext = "ciphertext-with-gcm-tag".getBytes(StandardCharsets.UTF_8);

        SessionBlobCodec.Blob blob = SessionBlobCodec.decode(
                SessionBlobCodec.encode(iv, ciphertext));

        assertArrayEquals(iv, blob.iv());
        assertArrayEquals(ciphertext, blob.ciphertext());
    }

    @Test
    public void formatIsSelfDescribingAboutIvLength() {
        // The Keystore emits 12-byte GCM IVs today, but the framing must not
        // assume that: any non-empty IV length round-trips unchanged.
        for (int length : new int[] {12, 16, 1, 31}) {
            byte[] iv = new byte[length];
            for (int index = 0; index < length; index++) iv[index] = (byte) (index + 1);
            SessionBlobCodec.Blob blob = SessionBlobCodec.decode(
                    SessionBlobCodec.encode(iv, new byte[] {42}));
            assertArrayEquals(iv, blob.iv());
        }
    }

    @Test
    public void usesTheDocumentedTwoSegmentBase64Framing() {
        // Standard base64 alphabet with padding and no line wraps, matching
        // what android.util.Base64.NO_WRAP historically produced.
        assertEquals("AQID.BAUG",
                SessionBlobCodec.encode(new byte[] {1, 2, 3}, new byte[] {4, 5, 6}));
        assertEquals("AQ==.BAUGBw==",
                SessionBlobCodec.encode(new byte[] {1}, new byte[] {4, 5, 6, 7}));
    }

    @Test
    public void rejectsMalformedBlobs() {
        assertThrows(IllegalArgumentException.class, () -> SessionBlobCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> SessionBlobCodec.decode(""));
        assertThrows(IllegalArgumentException.class, () -> SessionBlobCodec.decode("AQID"));
        assertThrows(IllegalArgumentException.class,
                () -> SessionBlobCodec.decode("AQID.BAUG.AQID"));
        assertThrows(IllegalArgumentException.class,
                () -> SessionBlobCodec.decode("!!invalid!!.BAUG"));
        assertThrows(IllegalArgumentException.class,
                () -> SessionBlobCodec.decode("AQID.!!invalid!!"));
        assertThrows(IllegalArgumentException.class, () -> SessionBlobCodec.decode(".BAUG"));
        assertThrows(IllegalArgumentException.class, () -> SessionBlobCodec.decode("AQID."));
    }

    @Test
    public void rejectsEncodingWithoutIvOrCiphertext() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionBlobCodec.encode(new byte[0], new byte[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> SessionBlobCodec.encode(null, new byte[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> SessionBlobCodec.encode(new byte[] {1}, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> SessionBlobCodec.encode(new byte[] {1}, null));
    }
}
