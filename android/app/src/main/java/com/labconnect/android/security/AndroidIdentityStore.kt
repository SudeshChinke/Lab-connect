package com.labconnect.android.security

import android.content.Context
import android.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PublicKeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security

/** Stable Ed25519 identity used by desktop discovery, HELLO, and pairing. */
class AndroidIdentityStore(context: Context) {
    data class Identity(val deviceId: String, val publicKeyBase64: String, val privateKeyBase64: String)

    private val preferences = context.getSharedPreferences("labconnect_identity", Context.MODE_PRIVATE)

    fun loadOrCreate(): Identity {
        val savedPublic = preferences.getString("public_key", null)
        val savedPrivate = preferences.getString("private_key", null)
        if (savedPublic != null && savedPrivate != null) {
            return Identity(deriveDeviceId(savedPublic), savedPublic, savedPrivate)
        }

        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val generator = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val pair: KeyPair = generator.generateKeyPair()
        // Desktop identities store and hash the raw 32-byte Ed25519 keys.
        // Keep this wire representation identical across platforms.
        val publicRaw = (PublicKeyFactory.createKey(pair.public.encoded) as Ed25519PublicKeyParameters).encoded
        val privateRaw = (PrivateKeyFactory.createKey(pair.private.encoded) as Ed25519PrivateKeyParameters).encoded
        val publicKey = Base64.encodeToString(publicRaw, Base64.NO_WRAP)
        val privateKey = Base64.encodeToString(privateRaw, Base64.NO_WRAP)
        preferences.edit().putString("public_key", publicKey).putString("private_key", privateKey).apply()
        return Identity(deriveDeviceId(publicKey), publicKey, privateKey)
    }

    private fun deriveDeviceId(publicKeyBase64: String): String {
        val encodedKey = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
        val digest = MessageDigest.getInstance("SHA-256").digest(encodedKey)
        return "DEVICE-" + digest.take(8).joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }
}
