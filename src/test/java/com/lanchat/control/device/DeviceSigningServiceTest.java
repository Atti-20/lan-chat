package com.lanchat.control.device;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceSigningServiceTest {

    @Test
    void signsCertificatesWithConfiguredEd25519Pair() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DeviceIdentityProperties properties = properties(pair);
        DeviceSigningService service = new DeviceSigningService(properties);
        service.initialize();

        String payload = "{\"deviceId\":7}";
        byte[] signatureBytes = Base64.getUrlDecoder().decode(service.sign(payload));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.getPublic());
        verifier.update(payload.getBytes(StandardCharsets.UTF_8));

        assertTrue(verifier.verify(signatureBytes));
        assertEquals(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                service.signingPublicKey());
        assertEquals(64, service.signingKeyFingerprint().length());
    }

    @Test
    void normalizesOnlyValidClientEd25519PublicKeys() throws Exception {
        KeyPair controlPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair devicePair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DeviceSigningService service = new DeviceSigningService(properties(controlPair));
        service.initialize();
        String encoded = Base64.getEncoder().encodeToString(devicePair.getPublic().getEncoded());

        assertEquals(encoded, service.validateAndNormalizeClientPublicKey("ed25519", encoded));
        assertEquals(64, service.fingerprint(encoded).length());
        assertThrows(IllegalArgumentException.class,
                () -> service.validateAndNormalizeClientPublicKey("RSA", encoded));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateAndNormalizeClientPublicKey("ED25519", "not-base64"));
    }

    @Test
    void disabledFeatureFailsClosedWithoutKeys() {
        DeviceSigningService service = new DeviceSigningService(new DeviceIdentityProperties());
        service.initialize();

        assertFalse(service.isEnabled());
        assertThrows(DeviceIdentityUnavailableException.class, service::requireEnabled);
    }

    @Test
    void enabledFeatureRejectsMissingOrMismatchedKeys() throws Exception {
        DeviceIdentityProperties missing = new DeviceIdentityProperties();
        missing.setEnabled(true);
        assertThrows(IllegalStateException.class,
                () -> new DeviceSigningService(missing).initialize());

        KeyPair first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DeviceIdentityProperties mismatched = new DeviceIdentityProperties();
        mismatched.setEnabled(true);
        mismatched.setSigningPrivateKey(
                Base64.getEncoder().encodeToString(first.getPrivate().getEncoded()));
        mismatched.setSigningPublicKey(
                Base64.getEncoder().encodeToString(second.getPublic().getEncoded()));
        assertThrows(IllegalStateException.class,
                () -> new DeviceSigningService(mismatched).initialize());
    }

    private DeviceIdentityProperties properties(KeyPair pair) {
        DeviceIdentityProperties properties = new DeviceIdentityProperties();
        properties.setEnabled(true);
        properties.setSigningPrivateKey(
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        properties.setSigningPublicKey(
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        return properties;
    }
}
