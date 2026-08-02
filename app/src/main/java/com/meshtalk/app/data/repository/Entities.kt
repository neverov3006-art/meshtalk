package com.meshtalk.app.data.repository

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    val publicKey: ByteArray, // raw X25519 public key bytes, exchanged during handshake
    val addedAt: Long
)

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val title: String,
    val isGroup: Boolean,
    val memberPeerIdsCsv: String, // comma-separated peerIds; simple for a first implementation
    val createdAt: Long
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderPeerId: String,
    val type: String,       // MessageType.name
    val text: String?,
    val lat: Double?,
    val lon: Double?,
    val timestamp: Long,
    val state: String,      // DeliveryState.name
    val transport: String?, // TransportKind.name
    val attachmentPath: String? = null,
    val attachmentFileName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentDurationMs: Int? = null,
    val attachmentSizeBytes: Long? = null
)

/** Envelopes we tried to send but no transport was available for — retried by MeshRelayWorker. */
@Entity(tableName = "pending_envelopes")
data class PendingEnvelopeEntity(
    @PrimaryKey val envelopeId: String,
    val chatId: String,
    val targetPeerId: String?,
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val ttl: Int,
    val timestamp: Long,
    val attempts: Int = 0
)
