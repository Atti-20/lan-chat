package com.lanchat.control.device;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class DeviceSigningService {

    private final DeviceIdentityProperties properties;
    private PrivateKey signingPrivateKey;
    private PublicKey signingPublicKey;
    private String signingPublicKeyBase64;
    private String signingKeyFingerprint;

    public DeviceSigningService(DeviceIdentityProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) return;
        if (!StringUtils.hasText(properties.getSigningPrivateKey())
                || !StringUtils.hasText(properties.getSigningPublicKey())) {
            throw new IllegalStateException(
                    "设备身份功能已启用，但未配置 Ed25519 Control 签名密钥对");
        }
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            byte[] privateBytes = Base64.getDecoder().decode(properties.getSigningPrivateKey().trim());
            byte[] publicBytes = Base64.getDecoder().decode(properties.getSigningPublicKey().trim());
            signingPrivateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
            signingPublicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicBytes));
            signingPublicKeyBase64 = Base64.getEncoder().encodeToString(signingPublicKey.getEncoded());
            signingKeyFingerprint = sha256Hex(signingPublicKey.getEncoded());
            byte[] probe = "meshx-device-signing-key-check".getBytes(StandardCharsets.UTF_8);
            if (!verify(probe, signBytes(probe))) {
                throw new IllegalStateException("Control 设备签名公私钥不匹配");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Control 设备签名密钥配置无效", exception);
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public void requireEnabled() {
        if (!isEnabled()) {
            throw new DeviceIdentityUnavailableException("设备身份功能尚未启用");
        }
    }

    public String sign(String payload) {
        requireEnabled();
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signBytes(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("设备身份签名失败", exception);
        }
    }

    public String validateAndNormalizeClientPublicKey(String algorithm, String encodedKey) {
        requireEnabled();
        if (!"ED25519".equalsIgnoreCase(algorithm)) {
            throw new IllegalArgumentException("当前只支持 ED25519 设备公钥");
        }
        if (!StringUtils.hasText(encodedKey) || encodedKey.length() > 256) {
            throw new IllegalArgumentException("设备公钥格式无效");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey.trim());
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(bytes));
            return Base64.getEncoder().encodeToString(key.getEncoded());
        } catch (Exception exception) {
            throw new IllegalArgumentException("设备公钥不是有效的 Ed25519 X.509 公钥");
        }
    }

    public String fingerprint(String normalizedPublicKey) {
        try {
            return sha256Hex(Base64.getDecoder().decode(normalizedPublicKey));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("设备公钥格式无效");
        }
    }

    public String signingPublicKey() {
        requireEnabled();
        return signingPublicKeyBase64;
    }

    public String signingKeyFingerprint() {
        requireEnabled();
        return signingKeyFingerprint;
    }

    private byte[] signBytes(byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signingPrivateKey);
        signature.update(payload);
        return signature.sign();
    }

    private boolean verify(byte[] payload, byte[] signed) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(signingPublicKey);
        signature.update(payload);
        return signature.verify(signed);
    }

    private String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
