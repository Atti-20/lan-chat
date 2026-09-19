package com.meshx.spike.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.core.content.edit
import com.meshx.spike.DeviceIdentity
import com.meshx.spike.DeviceIdentityStore
import com.meshx.spike.DeviceSecretProtection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
private const val PREFERENCES_NAME = "meshx_spike_device_identities"
private const val ENCRYPTED_SEED_VERSION = "v1"
private const val ED25519_SEED_SIZE = 32
private val ED25519_OBJECT_IDENTIFIER = ASN1ObjectIdentifier("1.3.101.112")

/**
 * Keeps an Ed25519 seed encrypted at rest with a non-exportable Android Keystore AES key.
 *
 * AndroidKeyStore does not expose Ed25519 as a native key algorithm, so the Ed25519 seed must
 * enter this process while signing. The protection label deliberately says "wrapped" rather
 * than claiming that the device signing key itself is hardware non-exportable.
 */
class AndroidDeviceIdentityStore(context: Context) : DeviceIdentityStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun loadOrCreate(controlId: String): DeviceIdentity = withContext(Dispatchers.IO) {
        synchronized(identityLock) {
            val key = normalizedControlKey(controlId)
            val seed = loadSeed(key) ?: createAndStoreSeed(key)
            try {
                identityFromSeed(seed, protectionFor(wrappingKey(key)))
            } finally {
                seed.fill(0)
            }
        }
    }

    override suspend fun sign(controlId: String, payload: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        synchronized(identityLock) {
            val key = normalizedControlKey(controlId)
            val seed = loadSeed(key)
                ?: throw IllegalStateException("device identity does not exist for this Control")
            try {
                val signer = Ed25519Signer()
                signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
                signer.update(payload, 0, payload.size)
                signer.generateSignature()
            } finally {
                seed.fill(0)
            }
        }
    }

    override suspend fun delete(controlId: String) = withContext(Dispatchers.IO) {
        synchronized(identityLock) {
            val key = normalizedControlKey(controlId)
            preferences.edit(commit = true) { remove(seedPreferenceKey(key)) }
            val keyStore = keyStore()
            if (keyStore.containsAlias(wrappingAlias(key))) {
                keyStore.deleteEntry(wrappingAlias(key))
            }
        }
    }

    private fun loadSeed(controlKey: String): ByteArray? {
        val encoded = preferences.getString(seedPreferenceKey(controlKey), null) ?: return null
        val keyStore = keyStore()
        val alias = wrappingAlias(controlKey)
        check(keyStore.containsAlias(alias)) {
            "encrypted device identity exists but its Android Keystore key is missing"
        }
        val key = (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Android Keystore device wrapping entry is invalid")
        val parts = encoded.split('.')
        check(parts.size == 3 && parts[0] == ENCRYPTED_SEED_VERSION) {
            "encrypted device identity has an unsupported format"
        }
        val iv = decode(parts[1])
        val ciphertext = decode(parts[2])
        check(iv.size == 12 && ciphertext.size >= ED25519_SEED_SIZE + 16) {
            "encrypted device identity is corrupt"
        }
        return try {
            Cipher.getInstance(AES_TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                updateAAD(controlKey.toByteArray(StandardCharsets.UTF_8))
                doFinal(ciphertext)
            }.also { check(it.size == ED25519_SEED_SIZE) { "decrypted device identity is invalid" } }
        } catch (exception: Exception) {
            throw IllegalStateException("unable to decrypt device identity; explicit re-registration is required", exception)
        }
    }

    private fun createAndStoreSeed(controlKey: String): ByteArray {
        val key = wrappingKey(controlKey)
        val seed = ByteArray(ED25519_SEED_SIZE).also(SecureRandom()::nextBytes)
        val encrypted = Cipher.getInstance(AES_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(controlKey.toByteArray(StandardCharsets.UTF_8))
            val ciphertext = doFinal(seed)
            "$ENCRYPTED_SEED_VERSION.${encode(iv)}.${encode(ciphertext)}"
        }
        preferences.edit(commit = true) { putString(seedPreferenceKey(controlKey), encrypted) }
        if (preferences.getString(seedPreferenceKey(controlKey), null) != encrypted) {
            seed.fill(0)
            keyStore().deleteEntry(wrappingAlias(controlKey))
            throw IllegalStateException("failed to persist encrypted device identity")
        }
        return seed
    }

    private fun wrappingKey(controlKey: String): SecretKey {
        val keyStore = keyStore()
        val alias = wrappingAlias(controlKey)
        if (keyStore.containsAlias(alias)) {
            return (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw IllegalStateException("Android Keystore device wrapping entry is invalid")
        }
        return generateWrappingKey(alias, preferStrongBox = supportsStrongBox())
    }

    private fun generateWrappingKey(alias: String, preferStrongBox: Boolean): SecretKey {
        fun generate(strongBox: Boolean): SecretKey {
            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (Build.VERSION.SDK_INT >= 28) builder.setIsStrongBoxBacked(strongBox)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
                init(builder.build())
                generateKey()
            }
        }

        return if (preferStrongBox && Build.VERSION.SDK_INT >= 28) {
            try {
                generate(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                generate(strongBox = false)
            }
        } else {
            generate(strongBox = false)
        }
    }

    private fun supportsStrongBox(): Boolean = Build.VERSION.SDK_INT >= 28 &&
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    @Suppress("DEPRECATION")
    private fun protectionFor(key: SecretKey): DeviceSecretProtection = try {
        val keyInfo = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (Build.VERSION.SDK_INT >= 31) {
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> DeviceSecretProtection.ANDROID_KEYSTORE_STRONGBOX_WRAPPED
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> DeviceSecretProtection.ANDROID_KEYSTORE_TEE_WRAPPED
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> DeviceSecretProtection.ANDROID_KEYSTORE_SOFTWARE_WRAPPED
                else -> DeviceSecretProtection.ANDROID_KEYSTORE_UNKNOWN_WRAPPED
            }
        } else if (keyInfo.isInsideSecureHardware) {
            DeviceSecretProtection.ANDROID_KEYSTORE_TEE_WRAPPED
        } else {
            DeviceSecretProtection.ANDROID_KEYSTORE_SOFTWARE_WRAPPED
        }
    } catch (_: Exception) {
        DeviceSecretProtection.ANDROID_KEYSTORE_UNKNOWN_WRAPPED
    }

    private fun identityFromSeed(seed: ByteArray, protection: DeviceSecretProtection): DeviceIdentity {
        val publicBytes = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        val publicDer = SubjectPublicKeyInfo(
            AlgorithmIdentifier(ED25519_OBJECT_IDENTIFIER),
            publicBytes,
        ).encoded
        val fingerprint = sha256Hex(publicDer)
        return DeviceIdentity(
            deviceKey = "android-${fingerprint.take(32)}",
            algorithm = "ED25519",
            publicKey = Base64.encodeToString(publicDer, Base64.NO_WRAP),
            fingerprint = fingerprint,
            secretProtection = protection,
        )
    }

    private fun normalizedControlKey(controlId: String): String {
        val normalized = controlId.trim()
        require(normalized.isNotEmpty() && normalized.length <= 512) { "Control ID is invalid" }
        return sha256Hex(normalized.toByteArray(StandardCharsets.UTF_8))
    }

    private fun wrappingAlias(controlKey: String) = "meshx-device-wrap-$controlKey"
    private fun seedPreferenceKey(controlKey: String) = "device-seed-$controlKey"
    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun sha256Hex(value: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val identityLock = Any()
    }
}
