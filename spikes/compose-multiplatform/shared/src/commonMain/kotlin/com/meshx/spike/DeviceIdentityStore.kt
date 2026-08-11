package com.meshx.spike

enum class DeviceSecretProtection {
    ANDROID_KEYSTORE_SOFTWARE_WRAPPED,
    ANDROID_KEYSTORE_TEE_WRAPPED,
    ANDROID_KEYSTORE_STRONGBOX_WRAPPED,
    ANDROID_KEYSTORE_UNKNOWN_WRAPPED,
}

data class DeviceIdentity(
    val deviceKey: String,
    val algorithm: String,
    val publicKey: String,
    val fingerprint: String,
    val secretProtection: DeviceSecretProtection,
)

interface DeviceIdentityStore {
    suspend fun loadOrCreate(controlId: String): DeviceIdentity
    suspend fun sign(controlId: String, payload: ByteArray): ByteArray
    suspend fun delete(controlId: String)
}
