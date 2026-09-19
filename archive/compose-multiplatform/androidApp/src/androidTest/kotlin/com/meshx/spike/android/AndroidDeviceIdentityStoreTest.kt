package com.meshx.spike.android

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshx.spike.DeviceSecretProtection
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDeviceIdentityStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = AndroidDeviceIdentityStore(context)
    private val controls = mutableSetOf<String>()

    @After
    fun cleanUp() = runBlocking {
        controls.forEach { store.delete(it) }
    }

    @Test
    fun identityPersistsAcrossStoreInstancesAndMatchesV28Shape() = runBlocking {
        val controlId = trackedControl("persistence")
        val first = store.loadOrCreate(controlId)
        val second = AndroidDeviceIdentityStore(context).loadOrCreate(controlId)

        assertEquals(first, second)
        assertEquals("ED25519", first.algorithm)
        assertTrue(first.deviceKey.matches(Regex("^android-[0-9a-f]{32}$")))
        assertTrue(first.fingerprint.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(SubjectPublicKeyInfo.getInstance(Base64.decode(first.publicKey, Base64.DEFAULT)) != null)
        assertNotEquals(DeviceSecretProtection.ANDROID_KEYSTORE_UNKNOWN_WRAPPED, first.secretProtection)
    }

    @Test
    fun signsWithTheSameEd25519Identity() = runBlocking {
        val controlId = trackedControl("signature")
        val identity = store.loadOrCreate(controlId)
        val payload = "meshx-peer-challenge".encodeToByteArray()
        val signature = store.sign(controlId, payload)
        val publicBytes = SubjectPublicKeyInfo
            .getInstance(Base64.decode(identity.publicKey, Base64.DEFAULT))
            .publicKeyData.bytes
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicBytes, 0))
            update(payload, 0, payload.size)
        }

        assertEquals(64, signature.size)
        assertTrue(verifier.verifySignature(signature))
    }

    @Test
    fun controlIsolationAndExplicitDeletionRotateIdentity() = runBlocking {
        val firstControl = trackedControl("control-a")
        val secondControl = trackedControl("control-b")
        val first = store.loadOrCreate(firstControl)
        val second = store.loadOrCreate(secondControl)
        assertNotEquals(first.fingerprint, second.fingerprint)

        store.delete(firstControl)
        controls.remove(firstControl)
        val rotated = store.loadOrCreate(firstControl)
        controls.add(firstControl)
        assertNotEquals(first.fingerprint, rotated.fingerprint)
        assertFalse(rotated.publicKey.isBlank())
    }

    private fun trackedControl(label: String): String =
        "control-$label-${System.nanoTime()}".also(controls::add)
}
