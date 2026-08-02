package com.meshtalk.app.data.model

import kotlinx.serialization.Serializable

/** Which physical link a message actually traveled over (for UI status / debugging). */
enum class TransportKind { BLUETOOTH, WIFI_DIRECT, INTERNET, RELAYED }

/** A single contact / peer identified by their long-term public key, not a phone number. */
data class Peer(
    val peerId: String,        // derived from public key, e.g. base58 fingerprint
    val displayName: String,
    val publicKey: ByteArray,
    val lastSeenTransport: TransportKind? = null
)

/** A conversation — either 1:1 or a closed group chat. All chats are encrypted. */
data class Chat(
    val chatId: String,
    val title: String,
    val isGroup: Boolean,
    val memberPeerIds: List<String>,
    val createdAt: Long
)

enum class MessageType { TEXT, LOCATION, AUDIO, FILE, SYSTEM }
enum class DeliveryState { SENDING, RELAYED, DELIVERED, FAILED }

/**
 * Wire-level payload. This is what actually gets serialized, encrypted, and sent over
 * whichever transport (BT / WiFi / Internet) and forwarded by relay nodes in the mesh.
 *
 * ratchetPublicKey/previousChainLength/messageNumber are the Double Ratchet header
 * (see crypto/DoubleRatchet.kt) — sent in the clear alongside the ciphertext, exactly as
 * in the Signal spec. They let the recipient's ratchet advance/catch up correctly even
 * if messages arrive out of order (which can genuinely happen over a multi-hop mesh).
 */
@Serializable
data class MeshEnvelope(
    val envelopeId: String,      // random UUID, used for dedup during relay flooding
    val chatId: String,
    val senderPeerId: String,
    val ciphertext: ByteArray,   // encrypted MessagePayload bytes
    val nonce: ByteArray,
    val ratchetPublicKey: ByteArray,
    val previousChainLength: Int,
    val messageNumber: Int,
    val ttl: Int,                 // hop limit for mesh relay, decremented at each hop
    val timestamp: Long
)

/**
 * Decrypted content of a MeshEnvelope.
 *
 * Audio/File carry the actual bytes base64-encoded inline, right alongside the rest of
 * the payload — encryption itself is still one-shot (the whole attachment goes through
 * a single Double Ratchet step, not encrypted chunk-by-chunk). What changed since the
 * first version: the *transports* no longer require the whole encrypted blob to fit in
 * one network frame. NearbyTransport streams large ciphertext as a Nearby Connections
 * FILE payload (chunking/retry handled by Google's library); InternetTransport splits
 * it into a sequence of small frames over the WebSocket relay and reassembles them on
 * the other end. See ChatRepository.MAX_ATTACHMENT_BYTES for the resulting practical
 * cap — no longer a transport packet-size limit, just a sane bound on memory use and
 * user patience.
 */
@Serializable
sealed class MessagePayload {
    @Serializable
    data class Text(val body: String) : MessagePayload()

    @Serializable
    data class Location(val lat: Double, val lon: Double, val label: String? = null) : MessagePayload()

    @Serializable
    data class Audio(val dataBase64: String, val durationMs: Int, val mimeType: String) : MessagePayload()

    @Serializable
    data class File(val dataBase64: String, val fileName: String, val mimeType: String, val sizeBytes: Long) : MessagePayload()
}

/** Local (decrypted, UI-facing) representation of a message stored in the DB. */
data class Message(
    val id: String,
    val chatId: String,
    val senderPeerId: String,
    val type: MessageType,
    val text: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val timestamp: Long,
    val state: DeliveryState,
    val transport: TransportKind?,
    /** For AUDIO/FILE: path to the decrypted bytes saved on local disk (see AttachmentStorage). */
    val attachmentPath: String? = null,
    val attachmentFileName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentDurationMs: Int? = null,
    val attachmentSizeBytes: Long? = null
)
