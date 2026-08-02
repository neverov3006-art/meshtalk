package com.meshtalk.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives for MeshTalk.
 *
 *  - Each device has a long-term identity key pair (X25519) held in the Android Keystore
 *    so the private key never leaves secure hardware where supported.
 *  - Per-message encryption is done by DoubleRatchet.kt / RatchetSessionManager, which
 *    uses the raw-key + HKDF primitives below (and reuses `encrypt`/`decrypt` as the
 *    underlying AES-256-GCM step once a message key has been derived) to give each
 *    message its own key — forward secrecy — instead of one static key per chat.
 *  - `deriveChatKey` below is the original static-per-chat-key scheme from before the
 *    ratchet was added; kept as a simple reference but no longer called anywhere.
 */
class CryptoManager {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val IDENTITY_KEY_ALIAS = "meshtalk_identity_key"
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12
    }

    /** Ensures this device has a long-term identity keypair, generating one on first run. */
    fun ensureIdentityKeyPair() {
        if (!keyStore.containsAlias(IDENTITY_KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_X25519, "AndroidKeyStore"
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    IDENTITY_KEY_ALIAS,
                    KeyProperties.PURPOSE_AGREE_KEY
                ).build()
            )
            generator.generateKeyPair()
        }
    }

    /** Public key bytes to publish/exchange with a peer (e.g. via QR code or Nearby handshake). */
    fun getIdentityPublicKey(): ByteArray {
        ensureIdentityKeyPair()
        return keyStore.getCertificate(IDENTITY_KEY_ALIAS).publicKey.encoded
    }

    /**
     * Derives a per-chat symmetric key by combining our private key with the peer's
     * public key via X25519, then stretching through HKDF-SHA256.
     */
    fun deriveChatKey(peerPublicKeyBytes: ByteArray): SecretKeySpec {
        ensureIdentityKeyPair()
        val privateKey = keyStore.getKey(IDENTITY_KEY_ALIAS, null)
        val peerPublicKey = java.security.KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_X25519)
            .generatePublic(java.security.spec.X509EncodedKeySpec(peerPublicKeyBytes))

        val agreement = KeyAgreement.getInstance(KeyProperties.KEY_ALGORITHM_X25519)
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()

        val derivedKeyBytes = hkdfSha256(sharedSecret, "meshtalk-chat-key-v1".toByteArray(), 32)
        return SecretKeySpec(derivedKeyBytes, "AES")
    }

    /** Encrypts plaintext bytes with AES-256-GCM using a fresh random nonce. Returns (nonce, ciphertext). */
    fun encrypt(key: SecretKeySpec, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return nonce to ciphertext
    }

    fun decrypt(key: SecretKeySpec, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    /**
     * DH agreement using OUR long-term identity private key (kept in AndroidKeyStore,
     * never exported) against an arbitrary peer public key. This is the only identity-key
     * operation the ratchet needs — see DoubleRatchet.kt for why: it's used once, to
     * bootstrap a new session's initial shared secret, and (for the session's responder
     * side only) once more during that session's very first DH-ratchet step.
     */
    fun identityDhAgreement(peerPublicKeyBytes: ByteArray): ByteArray {
        ensureIdentityKeyPair()
        val privateKey = keyStore.getKey(IDENTITY_KEY_ALIAS, null)
        val peerPublicKey = java.security.KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_X25519)
            .generatePublic(java.security.spec.X509EncodedKeySpec(peerPublicKeyBytes))
        val agreement = KeyAgreement.getInstance(KeyProperties.KEY_ALGORITHM_X25519)
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    data class RawKeyPair(val privateKeyBytes: ByteArray, val publicKeyBytes: ByteArray)

    /**
     * Generates a plain-software (non-Keystore) X25519 keypair. Used for ratchet steps,
     * where we need to persist the private key bytes ourselves (in Room) across restarts
     * and rotate keypairs frequently — AndroidKeyStore intentionally won't export private
     * key material, which is exactly right for the long-term identity key but unworkable
     * for a ratchet key that's replaced on nearly every DH step.
     */
    fun generateEphemeralX25519KeyPair(): RawKeyPair {
        val generator = KeyPairGenerator.getInstance("X25519")
        val keyPair = generator.generateKeyPair()
        return RawKeyPair(keyPair.private.encoded, keyPair.public.encoded)
    }

    /** Raw X25519 agreement between two plain (non-Keystore) key byte arrays. */
    fun rawDhAgreement(privateKeyBytes: ByteArray, publicKeyBytes: ByteArray): ByteArray {
        val keyFactory = java.security.KeyFactory.getInstance("X25519")
        val privateKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes))
        val publicKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyBytes))
        val agreement = KeyAgreement.getInstance("X25519")
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    /** General-purpose HKDF (RFC 5869) with an explicit salt — used by the ratchet's KDF_RK. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var previousBlock = ByteArray(0)
        var output = ByteArray(0)
        var counter = 1
        while (output.size < outputLength) {
            mac.reset()
            mac.update(previousBlock)
            mac.update(info)
            mac.update(counter.toByte())
            previousBlock = mac.doFinal()
            output += previousBlock
            counter++
        }
        return output.copyOf(outputLength)
    }

    /** Minimal HKDF (RFC 5869) using HMAC-SHA256, extract-then-expand. */
    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, outputLength: Int): ByteArray =
        hkdfSha256(ikm, ByteArray(32), info, outputLength)
}
