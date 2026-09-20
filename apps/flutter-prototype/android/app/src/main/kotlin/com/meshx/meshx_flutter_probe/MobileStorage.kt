package com.meshx.meshx_flutter_probe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials are encrypted with an unexportable Android Keystore key. */
class MobileStorage(context: Context, messenger: BinaryMessenger) {
    private val folder = File(context.noBackupFilesDir, "meshx-a05").apply { mkdirs() }
    private val credential = AtomicFile(File(folder, "credential.bin"))
    private val alias = "meshx.mobile.credential.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    init {
        MethodChannel(messenger, "com.meshx.mobile/storage").setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "dataDirectory" -> result.success(File(folder, "accounts").apply { mkdirs() }.path)
                    "clearCredential" -> {
                        credential.delete()
                        check(!credential.baseFile.exists()) { "Credential deletion failed" }
                        result.success(null)
                    }
                    "readCredential" -> {
                        if (!credential.baseFile.exists()) result.success(null)
                        else {
                            val bytes = credential.readFully()
                            require(bytes.size > 28)
                            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                            result.success(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
                        }
                    }
                    "writeCredential" -> {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.ENCRYPT_MODE, key())
                        val encrypted = cipher.iv + cipher.doFinal((call.arguments as String).toByteArray(Charsets.UTF_8))
                        val stream = credential.startWrite()
                        try { stream.write(encrypted); credential.finishWrite(stream) }
                        catch (error: Exception) { credential.failWrite(stream); throw error }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            } catch (error: Exception) {
                result.error("STORAGE_UNAVAILABLE", "安全存储或本地数据不可用，请重试", null)
            }
        }
    }
}
