package com.labconnect.android.security

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.SecureRandom
import java.security.MessageDigest

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

        // Desktop identities store and hash the raw 32-byte Ed25519 keys.
        // Keep this wire representation identical across platforms.
        // Use Bouncy Castle's lightweight API directly: Android's built-in provider
        // is also named "BC" on some versions, but does not implement Ed25519.
        val privateKeyParameters = Ed25519PrivateKeyParameters(SecureRandom())
        val publicKeyParameters: Ed25519PublicKeyParameters = privateKeyParameters.generatePublicKey()
        val publicRaw = publicKeyParameters.encoded
        val privateRaw = privateKeyParameters.encoded
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
