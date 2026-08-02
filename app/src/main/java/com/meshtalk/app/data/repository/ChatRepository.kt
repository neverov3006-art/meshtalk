package com.meshtalk.app.data.repository

import com.meshtalk.app.attachments.AttachmentStorage
import com.meshtalk.app.crypto.RatchetSessionManager
import com.meshtalk.app.data.model.*
import com.meshtalk.app.mesh.MeshRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Single source of truth for chats/messages/peers. Bridges the pieces built so far:
 *  - RatchetSessionManager (crypto/DoubleRatchet.kt): per-message encryption keys with
 *    forward secrecy, replacing the earlier static-per-chat-key scheme.
 *  - Room (Daos/Entities): durable local storage, survives app restarts.
 *  - MeshRouter: actually gets encrypted envelopes onto the wire (BT/WiFi/Internet).
 *  - AttachmentStorage: decrypted voice-note/file bytes live on disk, not as DB blobs.
 *
 * Group-chat note: encryption is pairwise (one ratchet session, and one ciphertext, per
 * recipient) rather than a single shared group key/ratchet. That's simple and secure —
 * each member gets genuine forward secrecy on their own session — but means group sends
 * do one encrypt+send per member; fine for small friend groups, worth revisiting (sender
 * keys, à la Signal groups) if group sizes grow.
 */
class ChatRepository(
    private val db: MeshTalkDatabase,
    private val ratchet: RatchetSessionManager,
    private val meshRouter: MeshRouter,
    private val attachments: AttachmentStorage,
    private val localPeerId: String
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /**
         * Both transports now stream large attachments in pieces rather than one giant
         * frame — NearbyTransport hands them to Nearby Connections' own FILE payload
         * (chunking/flow-control handled by Google's library), and InternetTransport
         * splits them into ENVELOPE_CHUNK frames over the WebSocket. So this cap is no
         * longer about any single transport packet limit; it's about being a reasonable
         * mobile citizen — holding a whole attachment (plus its base64 encoding, plus the
         * decrypted copy) in memory at once, and not making someone wait forever to send
         * a "voice message" that's actually a video. Encryption is still one shot (the
         * whole attachment as a single Double Ratchet message, not encrypted per-chunk),
         * so this is also the practical ceiling for how much a single AES-GCM call
         * reasonably handles at once.
         */
        const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024 // 25 MB
    }

    class AttachmentTooLargeException(val actualSizeBytes: Long) :
        Exception("Attachment is ${actualSizeBytes / 1024}KB, exceeds the $MAX_ATTACHMENT_BYTES-byte cap")

    fun observeChats(): Flow<List<ChatEntity>> = db.chatDao().observeAll()
    fun observeMessages(chatId: String): Flow<List<Message>> =
        db.messageDao().observeForChat(chatId).map { list -> list.map { it.toModel() } }
    fun observePeers(): Flow<List<PeerEntity>> = db.peerDao().observeAll()

    /** Called by NearbyTransport (via the handshake listener) the moment we learn a new
     *  peer's identity+public key — either a brand-new contact or one reconnecting. */
    fun onPeerHandshake(peerId: String, publicKey: ByteArray) {
        scope.launch {
            val existing = db.peerDao().getByPeerId(peerId)
            db.peerDao().upsert(
                PeerEntity(
                    peerId = peerId,
                    displayName = existing?.displayName ?: peerId.take(8), // placeholder name until user renames the contact
                    publicKey = publicKey,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis()
                )
            )
            // Once we have their key, flush anything queued for them.
            retryPendingEnvelopes()
        }
    }

    /**
     * Saves a contact scanned from a QR invite (see InviteCode) and creates a 1:1 chat
     * with them right away. Unlike onPeerHandshake, this always trusts the provided
     * displayName since the user explicitly scanned it — the Nearby handshake path
     * instead keeps whatever name the user already set locally.
     *
     * Returns the new (or pre-existing) chatId so the caller can navigate straight in.
     */
    suspend fun addScannedContactAndOpenChat(decoded: InviteCode.Decoded): String {
        db.peerDao().upsert(
            PeerEntity(
                peerId = decoded.peerId,
                displayName = decoded.displayName,
                publicKey = decoded.publicKey,
                addedAt = System.currentTimeMillis()
            )
        )
        // Note: this always creates a fresh chat rather than reusing an existing 1:1 with
        // the same peer. Room's Flow-based DAO has no one-shot "get chats now" query yet;
        // adding one (and de-duping here) is a good small follow-up if re-scanning a friend
        // ends up creating visible duplicate chats in practice.
        return createChat(title = decoded.displayName, memberPeerIds = listOf(decoded.peerId), isGroup = false)
    }

    /** Starts collecting decrypted-eligible envelopes coming from the mesh router. */
    fun startListeningForIncoming() {
        scope.launch {
            meshRouter.deliveredForUs.collect { envelope -> handleIncomingEnvelope(envelope) }
        }
    }

    /** Creates a new 1:1 or group chat from already-known peers (their handshake must have happened already). */
    suspend fun createChat(title: String, memberPeerIds: List<String>, isGroup: Boolean): String {
        val chatId = UUID.randomUUID().toString()
        db.chatDao().upsert(
            ChatEntity(
                chatId = chatId,
                title = title,
                isGroup = isGroup,
                memberPeerIdsCsv = memberPeerIds.joinToString(","),
                createdAt = System.currentTimeMillis()
            )
        )
        return chatId
    }

    suspend fun sendText(chatId: String, text: String) =
        sendPayload(chatId, MessagePayload.Text(text)) { it.copy(text = text, type = MessageType.TEXT) }

    suspend fun sendLocation(chatId: String, lat: Double, lon: Double, label: String? = null) =
        sendPayload(chatId, MessagePayload.Location(lat, lon, label)) {
            it.copy(lat = lat, lon = lon, type = MessageType.LOCATION)
        }

    /** Encrypts and sends a recorded voice note. Throws [AttachmentTooLargeException] if it exceeds [MAX_ATTACHMENT_BYTES]. */
    suspend fun sendVoice(chatId: String, recordingFile: File, durationMs: Int, mimeType: String) {
        val bytes = recordingFile.readBytes()
        if (bytes.size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(bytes.size.toLong())

        // Keep our own copy so the sender's chat bubble can play it back too, independent
        // of the temp recording file (which the caller may delete after this returns).
        val savedFile = attachments.save(bytes, "voice.m4a")
        val payload = MessagePayload.Audio(
            dataBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            durationMs = durationMs,
            mimeType = mimeType
        )
        sendPayload(chatId, payload) {
            it.copy(
                type = MessageType.AUDIO,
                attachmentPath = savedFile.absolutePath,
                attachmentMimeType = mimeType,
                attachmentDurationMs = durationMs,
                attachmentSizeBytes = bytes.size.toLong()
            )
        }
    }

    /** Encrypts and sends a file picked by the user. Throws [AttachmentTooLargeException] if it exceeds [MAX_ATTACHMENT_BYTES]. */
    suspend fun sendFile(chatId: String, bytes: ByteArray, fileName: String, mimeType: String) {
        if (bytes.size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(bytes.size.toLong())

        val savedFile = attachments.save(bytes, fileName)
        val payload = MessagePayload.File(
            dataBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong()
        )
        sendPayload(chatId, payload) {
            it.copy(
                type = MessageType.FILE,
                attachmentPath = savedFile.absolutePath,
                attachmentFileName = fileName,
                attachmentMimeType = mimeType,
                attachmentSizeBytes = bytes.size.toLong()
            )
        }
    }

    private suspend fun sendPayload(
        chatId: String,
        payload: MessagePayload,
        applyToMessage: (Message) -> Message
    ) {
        val chat = db.chatDao().getByChatId(chatId) ?: return
        val memberIds = chat.memberPeerIdsCsv.split(",").filter { it.isNotBlank() }
        val messageId = UUID.randomUUID().toString()
        val plaintext = json.encodeToString(payload).toByteArray()

        // Persist locally immediately (optimistic UI: shows as SENDING right away).
        val localMessage = applyToMessage(
            Message(
                id = messageId, chatId = chatId, senderPeerId = localPeerId,
                type = MessageType.TEXT, timestamp = System.currentTimeMillis(),
                state = DeliveryState.SENDING, transport = null
            )
        )
        db.messageDao().upsert(localMessage.toEntity())

        var anySent = false
        for (memberPeerId in memberIds) {
            if (memberPeerId == localPeerId) continue
            val peer = db.peerDao().getByPeerId(memberPeerId)
            if (peer == null) {
                // We don't have their public key yet (never handshaked) — queue for retry.
                queuePending(chatId, memberPeerId, payload)
                continue
            }
            // Each send advances this (chatId, memberPeerId) ratchet by one step, giving
            // this specific message its own never-reused key — see RatchetSessionManager.
            val encrypted = ratchet.encrypt(chatId, memberPeerId, peer.publicKey, plaintext)
            val envelope = MeshEnvelope(
                envelopeId = UUID.randomUUID().toString(),
                chatId = chatId,
                senderPeerId = localPeerId,
                ciphertext = encrypted.ciphertext,
                nonce = encrypted.nonce,
                ratchetPublicKey = encrypted.ratchetPublicKey,
                previousChainLength = encrypted.previousChainLength,
                messageNumber = encrypted.messageNumber,
                ttl = meshRouter.newEnvelopeTtl(),
                timestamp = System.currentTimeMillis()
            )
            meshRouter.sendEnvelope(envelope, targetPeerId = memberPeerId)
            anySent = true
        }

        db.messageDao().updateDeliveryState(
            messageId,
            state = (if (anySent) DeliveryState.RELAYED else DeliveryState.FAILED).name,
            transport = null
        )
    }

    private fun queuePending(chatId: String, targetPeerId: String, payload: MessagePayload) {
        scope.launch {
            db.pendingEnvelopeDao().upsert(
                PendingEnvelopeEntity(
                    envelopeId = UUID.randomUUID().toString(),
                    chatId = chatId,
                    targetPeerId = targetPeerId,
                    // Held as plaintext JSON bytes until the peer's key is known — the
                    // ratchet only advances (and only produces a valid header+ciphertext)
                    // once we can actually encrypt, so there's nothing meaningful to
                    // ratchet-encrypt yet. Field name kept as "ciphertext" to match
                    // PendingEnvelopeEntity's schema; see retryPendingEnvelopes().
                    ciphertext = json.encodeToString(payload).toByteArray(),
                    nonce = ByteArray(0),
                    ttl = meshRouter.newEnvelopeTtl(),
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    /** Called after a handshake resolves a missing key, or periodically from a WorkManager job. */
    private suspend fun retryPendingEnvelopes() {
        val pending = db.pendingEnvelopeDao().getAll()
        for (entry in pending) {
            val targetPeerId = entry.targetPeerId ?: continue
            val peer = db.peerDao().getByPeerId(targetPeerId) ?: continue
            val encrypted = ratchet.encrypt(entry.chatId, targetPeerId, peer.publicKey, entry.ciphertext)
            val envelope = MeshEnvelope(
                envelopeId = entry.envelopeId, chatId = entry.chatId, senderPeerId = localPeerId,
                ciphertext = encrypted.ciphertext, nonce = encrypted.nonce,
                ratchetPublicKey = encrypted.ratchetPublicKey,
                previousChainLength = encrypted.previousChainLength,
                messageNumber = encrypted.messageNumber,
                ttl = entry.ttl, timestamp = entry.timestamp
            )
            meshRouter.sendEnvelope(envelope, targetPeerId)
            db.pendingEnvelopeDao().delete(entry.envelopeId)
        }
    }

    private suspend fun handleIncomingEnvelope(envelope: MeshEnvelope) {
        val chat = db.chatDao().getByChatId(envelope.chatId) ?: return // not a chat we're part of
        val senderPeer = db.peerDao().getByPeerId(envelope.senderPeerId) ?: return // unknown sender, no key yet

        val plaintext = ratchet.decrypt(
            chatId = envelope.chatId,
            remotePeerId = envelope.senderPeerId,
            remoteIdentityPublicKey = senderPeer.publicKey,
            message = RatchetSessionManager.EncryptedMessage(
                ratchetPublicKey = envelope.ratchetPublicKey,
                previousChainLength = envelope.previousChainLength,
                messageNumber = envelope.messageNumber,
                nonce = envelope.nonce,
                ciphertext = envelope.ciphertext
            )
        ) ?: return // not actually for us / wrong session — MeshRouter already relayed it onward

        val payload = runCatching { json.decodeFromString<MessagePayload>(String(plaintext)) }.getOrNull() ?: return

        val message = when (payload) {
            is MessagePayload.Text -> Message(
                id = envelope.envelopeId, chatId = envelope.chatId, senderPeerId = envelope.senderPeerId,
                type = MessageType.TEXT, text = payload.body, timestamp = envelope.timestamp,
                state = DeliveryState.DELIVERED, transport = null
            )
            is MessagePayload.Location -> Message(
                id = envelope.envelopeId, chatId = envelope.chatId, senderPeerId = envelope.senderPeerId,
                type = MessageType.LOCATION, lat = payload.lat, lon = payload.lon, timestamp = envelope.timestamp,
                state = DeliveryState.DELIVERED, transport = null
            )
            is MessagePayload.Audio -> {
                val bytes = android.util.Base64.decode(payload.dataBase64, android.util.Base64.NO_WRAP)
                val savedFile = attachments.save(bytes, "voice.m4a")
                Message(
                    id = envelope.envelopeId, chatId = envelope.chatId, senderPeerId = envelope.senderPeerId,
                    type = MessageType.AUDIO, timestamp = envelope.timestamp,
                    state = DeliveryState.DELIVERED, transport = null,
                    attachmentPath = savedFile.absolutePath, attachmentMimeType = payload.mimeType,
                    attachmentDurationMs = payload.durationMs, attachmentSizeBytes = bytes.size.toLong()
                )
            }
            is MessagePayload.File -> {
                val bytes = android.util.Base64.decode(payload.dataBase64, android.util.Base64.NO_WRAP)
                val savedFile = attachments.save(bytes, payload.fileName)
                Message(
                    id = envelope.envelopeId, chatId = envelope.chatId, senderPeerId = envelope.senderPeerId,
                    type = MessageType.FILE, timestamp = envelope.timestamp,
                    state = DeliveryState.DELIVERED, transport = null,
                    attachmentPath = savedFile.absolutePath, attachmentFileName = payload.fileName,
                    attachmentMimeType = payload.mimeType, attachmentSizeBytes = payload.sizeBytes
                )
            }
        }
        db.messageDao().upsert(message.toEntity())
    }
}

private fun Message.toEntity() = MessageEntity(
    id = id, chatId = chatId, senderPeerId = senderPeerId, type = type.name,
    text = text, lat = lat, lon = lon, timestamp = timestamp, state = state.name,
    transport = transport?.name,
    attachmentPath = attachmentPath, attachmentFileName = attachmentFileName,
    attachmentMimeType = attachmentMimeType, attachmentDurationMs = attachmentDurationMs,
    attachmentSizeBytes = attachmentSizeBytes
)

private fun MessageEntity.toModel() = Message(
    id = id, chatId = chatId, senderPeerId = senderPeerId, type = MessageType.valueOf(type),
    text = text, lat = lat, lon = lon, timestamp = timestamp, state = DeliveryState.valueOf(state),
    transport = transport?.let { TransportKind.valueOf(it) },
    attachmentPath = attachmentPath, attachmentFileName = attachmentFileName,
    attachmentMimeType = attachmentMimeType, attachmentDurationMs = attachmentDurationMs,
    attachmentSizeBytes = attachmentSizeBytes
)
