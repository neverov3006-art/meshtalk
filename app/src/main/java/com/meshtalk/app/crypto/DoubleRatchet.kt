package com.meshtalk.app.crypto

import javax.crypto.spec.SecretKeySpec

/**
 * Double Ratchet primitives, following https://signal.org/docs/specifications/doubleratchet/
 * variable-for-variable where practical (RK, CKs/CKr, Ns/Nr, PN, DHs/DHr) so this can be
 * checked directly against the spec.
 *
 * Session bootstrap (the one deliberate simplification vs. full Signal/X3DH):
 * MeshTalk has no prekey server, so instead of a signed prekey, the session's very
 * first DH exchange reuses each side's long-term identity key as its bootstrap ratchet
 * key:
 *   - The initiator (whoever sends first) generates a fresh ephemeral ratchet keypair
 *     and treats the recipient's *identity* public key as the recipient's first DHr.
 *   - The recipient, on first receive, treats their OWN identity key as their initial
 *     DHs for that one exchange (computed via CryptoManager.identityDhAgreement so the
 *     Keystore-held private key never needs exporting), then immediately generates a
 *     fresh ephemeral keypair for everything after.
 *
 * Practical effect: the very first message in a chat has the same forward-secrecy
 * profile as the old static-key scheme (its security rests on both identity keys not
 * being compromised at that moment). Every message after that gets full Double Ratchet
 * forward secrecy — a compromise of any single ratchet key can't decrypt past or future
 * traffic. Closing that one remaining gap means adding real signed prekeys (X3DH), which
 * would need a small key-server to publish them; noted as a follow-up.
 */
object DoubleRatchet {

    private const val MAX_SKIPPED_KEYS = 200 // bound on stored skipped keys, per session

    data class Header(val dhPublicKey: ByteArray, val previousChainLength: Int, val messageNumber: Int)

    data class EncryptResult(val header: Header, val nonce: ByteArray, val ciphertext: ByteArray)

    /** RatchetInitAlice: called by whoever sends the first message in a new chat. */
    fun initAsInitiator(
        crypto: CryptoManager,
        sharedSecret: ByteArray,
        remoteIdentityPublicKey: ByteArray
    ): RatchetState {
        val ephemeral = crypto.generateEphemeralX25519KeyPair()
        val dhOut = crypto.rawDhAgreement(ephemeral.privateKeyBytes, remoteIdentityPublicKey)
        val (rootKey, chainKeySend) = kdfRk(crypto, sharedSecret, dhOut)
        return RatchetState(
            rootKey = rootKey,
            dhSelfPrivate = ephemeral.privateKeyBytes,
            dhSelfPublic = ephemeral.publicKeyBytes,
            dhRemotePublic = remoteIdentityPublicKey,
            chainKeySend = chainKeySend,
            chainKeyRecv = null,
            messageNumberSend = 0,
            messageNumberRecv = 0,
            previousChainLength = 0,
            skippedKeys = emptyMap()
        )
    }

    /**
     * RatchetInitBob: called the first time we receive a message in a chat we have no
     * session for yet. dhSelfPrivate = null is the sentinel meaning "use our identity
     * key from the Keystore" for the one DH computation this session needs it for —
     * see performDhRatchetStep, which clears the sentinel right after.
     */
    fun initAsResponder(localIdentityPublicKey: ByteArray, sharedSecret: ByteArray): RatchetState =
        RatchetState(
            rootKey = sharedSecret,
            dhSelfPrivate = null, // sentinel: use identity key
            dhSelfPublic = localIdentityPublicKey,
            dhRemotePublic = null,
            chainKeySend = null,
            chainKeyRecv = null,
            messageNumberSend = 0,
            messageNumberRecv = 0,
            previousChainLength = 0,
            skippedKeys = emptyMap()
        )

    fun encrypt(crypto: CryptoManager, state: RatchetState, plaintext: ByteArray): Pair<RatchetState, EncryptResult> {
        requireNotNull(state.chainKeySend) { "No sending chain yet — this session hasn't completed its first DH ratchet step" }
        val (newChainKey, messageKey) = kdfCk(state.chainKeySend)
        val header = Header(state.dhSelfPublic, state.previousChainLength, state.messageNumberSend)
        val (nonce, ciphertext) = crypto.encrypt(SecretKeySpec(messageKey, "AES"), plaintext)
        val newState = state.copy(chainKeySend = newChainKey, messageNumberSend = state.messageNumberSend + 1)
        return newState to EncryptResult(header, nonce, ciphertext)
    }

    /** Returns the updated state and decrypted plaintext, or null if the message key was invalid/duplicate. */
    fun decrypt(
        crypto: CryptoManager,
        state: RatchetState,
        header: Header,
        nonce: ByteArray,
        ciphertext: ByteArray
    ): Pair<RatchetState, ByteArray>? {
        // 1. Try skipped-key cache first (handles out-of-order / retried delivery).
        val skippedKeyEntry = state.skippedKeys[skippedKeyId(header)]
        if (skippedKeyEntry != null) {
            val plaintext = runCatching { crypto.decrypt(SecretKeySpec(skippedKeyEntry, "AES"), nonce, ciphertext) }.getOrNull()
                ?: return null
            val newState = state.copy(skippedKeys = state.skippedKeys - skippedKeyId(header))
            return newState to plaintext
        }

        var workingState = state

        // 2. New DH ratchet step needed if the sender's ratchet key has advanced.
        if (state.dhRemotePublic == null || !header.dhPublicKey.contentEquals(state.dhRemotePublic)) {
            workingState = skipMessageKeys(workingState, header.previousChainLength)
            workingState = performDhRatchetStep(crypto, workingState, header.dhPublicKey)
        }

        workingState = skipMessageKeys(workingState, header.messageNumber)

        val chainKeyRecv = workingState.chainKeyRecv
            ?: return null // shouldn't happen post-ratchet-step, but guards a malformed/replayed header
        val (newChainKey, messageKey) = kdfCk(chainKeyRecv)
        val plaintext = runCatching { crypto.decrypt(SecretKeySpec(messageKey, "AES"), nonce, ciphertext) }.getOrNull()
            ?: return null

        workingState = workingState.copy(
            chainKeyRecv = newChainKey,
            messageNumberRecv = workingState.messageNumberRecv + 1
        )
        return workingState to plaintext
    }

    private fun performDhRatchetStep(crypto: CryptoManager, state: RatchetState, remoteDhPublicKey: ByteArray): RatchetState {
        // DH(state.DHs, remoteDhPublicKey) — using the identity key if DHs is still the
        // bootstrap sentinel (only true for a responder's very first ratchet step).
        val dhOutRecv = if (state.dhSelfPrivate == null) {
            crypto.identityDhAgreement(remoteDhPublicKey)
        } else {
            crypto.rawDhAgreement(state.dhSelfPrivate, remoteDhPublicKey)
        }
        val (rkAfterRecv, ckRecv) = kdfRk(crypto, state.rootKey, dhOutRecv)

        // Generate a fresh ephemeral keypair for all future sending — from this point on,
        // the identity key is never touched again for this session.
        val newEphemeral = crypto.generateEphemeralX25519KeyPair()
        val dhOutSend = crypto.rawDhAgreement(newEphemeral.privateKeyBytes, remoteDhPublicKey)
        val (rkAfterSend, ckSend) = kdfRk(crypto, rkAfterRecv, dhOutSend)

        return state.copy(
            rootKey = rkAfterSend,
            dhSelfPrivate = newEphemeral.privateKeyBytes,
            dhSelfPublic = newEphemeral.publicKeyBytes,
            dhRemotePublic = remoteDhPublicKey,
            chainKeySend = ckSend,
            chainKeyRecv = ckRecv,
            previousChainLength = state.messageNumberSend,
            messageNumberSend = 0,
            messageNumberRecv = 0
        )
    }

    /** Advances the receiving chain, caching any message keys we skip past, up to a bound. */
    private fun skipMessageKeys(state: RatchetState, untilN: Int): RatchetState {
        val chainKeyRecv = state.chainKeyRecv ?: return state
        if (state.messageNumberRecv >= untilN) return state

        var ck = chainKeyRecv
        var skipped = state.skippedKeys
        var n = state.messageNumberRecv
        while (n < untilN) {
            val (nextCk, messageKey) = kdfCk(ck)
            val id = "${b64(state.dhRemotePublic ?: ByteArray(0))}:$n"
            skipped = (skipped + (id to messageKey)).let { map ->
                if (map.size > MAX_SKIPPED_KEYS) map.entries.drop(map.size - MAX_SKIPPED_KEYS).associate { it.key to it.value }
                else map
            }
            ck = nextCk
            n++
        }
        return state.copy(chainKeyRecv = ck, messageNumberRecv = n, skippedKeys = skipped)
    }

    private fun skippedKeyId(header: Header) = "${b64(header.dhPublicKey)}:${header.messageNumber}"

    private fun kdfRk(crypto: CryptoManager, rootKey: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val output = crypto.hkdfSha256(ikm = dhOut, salt = rootKey, info = "MeshTalkRatchetRK".toByteArray(), outputLength = 64)
        return output.copyOfRange(0, 32) to output.copyOfRange(32, 64)
    }

    private fun kdfCk(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
        val newChainKey = mac.doFinal(byteArrayOf(0x02))
        mac.reset()
        val messageKey = mac.doFinal(byteArrayOf(0x01))
        return newChainKey to messageKey
    }

    private fun b64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

/** Immutable snapshot of one Double Ratchet session's state. See RatchetSessionEntity for the persisted form. */
data class RatchetState(
    val rootKey: ByteArray,
    val dhSelfPrivate: ByteArray?, // null = bootstrap sentinel, "use identity key" (responder only, pre-first-ratchet-step)
    val dhSelfPublic: ByteArray,
    val dhRemotePublic: ByteArray?,
    val chainKeySend: ByteArray?,
    val chainKeyRecv: ByteArray?,
    val messageNumberSend: Int,
    val messageNumberRecv: Int,
    val previousChainLength: Int,
    val skippedKeys: Map<String, ByteArray> // "dhPublicKeyB64:n" -> messageKey
)
