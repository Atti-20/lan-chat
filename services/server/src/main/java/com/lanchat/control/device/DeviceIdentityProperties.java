package com.lanchat.control.device;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "meshx.device-identity")
public class DeviceIdentityProperties {
    private boolean enabled;
    /** Base64 PKCS#8 Ed25519 private key. */
    private String signingPrivateKey = "";
    /** Base64 X.509 Ed25519 public key. */
    private String signingPublicKey = "";
}
