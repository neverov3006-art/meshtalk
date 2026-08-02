package com.meshtalk.app.crypto

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Ties DoubleRatchet's pure algorithm to durable storage (Room) and to CryptoManager's
 * identity-key operations. One RatchetSessionManager per app instance; one session row
 * per (chatId, remotePeerId) pair — see RatchetSessionEntity for why group chats get one
 * row per member rather than a single shared ratchet.
 */
class RatchetSessionManager(
    private val dao: RatchetSessionDao,
    private val crypto: CryptoManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class EncryptedMessage(
        val ratchetPublicKey: ByteArray,
        val previousChainLength: Int,
        val messageNumber: Int,
        val nonce: ByteArray,
        val ciphertext: ByteArray
    )

    /**
     * Encrypts [plaintext] for [remotePeerId] within [chatId], creating a new ratchet
     * session (as initiator) if one doesn't exist yet.
     */
    suspend fun encrypt(
        chatId: String,
        remotePeerId: String,
        remoteIdentityPublicKey: ByteArray,
        plaintext: ByteArray
    ): EncryptedMessage {
        val state = loadState(chatId, remotePeerId)
            ?: DoubleRatchet.initAsInitiator(
                crypto = crypto,
                sharedSecret = crypto.identityDhAgreement(remoteIdentityPublicKey),
                remoteIdentityPublicKey = remoteIdentityPublicKey
            )

        val (newState, result) = DoubleRatchet.encrypt(crypto, state, plaintext)
        saveState(chatId, remotePeerId, newState)

        return EncryptedMessage(
            ratchetPublicKey = result.header.dhPublicKey,
            previousChainLength = result.header.previousChainLength,
            messageNumber = result.header.messageNumber,
            nonce = result.nonce,
            ciphertext = result.ciphertext
        )
    }

    /**
     * Decrypts a message from [remotePeerId] within [chatId], bootstrapping a new session
     * (as responder) on first contact. Returns null if decryption fails — e.g. this
     * envelope wasn't actually for us, or it's a mesh-relay duplicate already consumed.
     */
    suspend fun decrypt(
        chatId: String,
        remotePeerId: String,
        remoteIdentityPublicKey: ByteArray,
        message: EncryptedMessage
    ): ByteArray? {
        val state = loadState(chatId, remotePeerId)
            ?: DoubleRatchet.initAsResponder(
                localIdentityPublicKey = crypto.getIdentityPublicKey(),
                sharedSecret = crypto.identityDhAgreement(remoteIdentityPublicKey)
            )

        val header = DoubleRatchet.Header(message.ratchetPublicKey, message.previousChainLength, message.messageNumber)
        val result = DoubleRatchet.decrypt(crypto, state, header, message.nonce, message.ciphertext) ?: return null
        val (newState, plaintext) = result
        saveState(chatId, remotePeerId, newState)
        return plaintext
    }

    private suspend fun loadState(chatId: String, remotePeerId: String): RatchetState? {
        val entity = dao.get(chatId, remotePeerId) ?: return null
        return RatchetState(
            rootKey = entity.rootKey,
            dhSelfPrivate = entity.dhSelfPrivate.takeIf { it.isNotEmpty() }, // empty = sentinel, see saveState()
            dhSelfPublic = entity.dhSelfPublic,
            dhRemotePublic = entity.dhRemotePublic,
            chainKeySend = entity.chainKeySend,
            chainKeyRecv = entity.chainKeyRecv,
            messageNumberSend = entity.messageNumberSend,
            messageNumberRecv = entity.messageNumberRecv,
            previousChainLength = entity.previousChainLength,
            skippedKeys = decodeSkippedKeys(entity.skippedKeysJson)
        )
    }

    private suspend fun saveState(chatId: String, remotePeerId: String, state: RatchetState) {
        dao.upsert(
            RatchetSessionEntity(
                chatId = chatId,
                remotePeerId = remotePeerId,
                rootKey = state.rootKey,
                dhSelfPrivate = state.dhSelfPrivate ?: ByteArray(0), // Room needs non-null; empty = sentinel
                dhSelfPublic = state.dhSelfPublic,
                dhRemotePublic = state.dhRemotePublic,
                chainKeySend = state.chainKeySend,
                chainKeyRecv = state.chainKeyRecv,
                messageNumberSend = state.messageNumberSend,
                messageNumberRecv = state.messageNumberRecv,
                previousChainLength = state.previousChainLength,
                skippedKeysJson = encodeSkippedKeys(state.skippedKeys)
            )
        )
    }

    @Serializable
    private data class SkippedKeyEntry(val id: String, val keyB64: String)

    private fun encodeSkippedKeys(keys: Map<String, ByteArray>): String =
        json.encodeToString(keys.map { (id, key) -> SkippedKeyEntry(id, Base64.encodeToString(key, Base64.NO_WRAP)) })

    private fun decodeSkippedKeys(raw: String): Map<String, ByteArray> {
        if (raw.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<List<SkippedKeyEntry>>(raw) }
            .getOrDefault(emptyList())
            .associate { it.id to Base64.decode(it.keyB64, Base64.NO_WRAP) }
    }
}
