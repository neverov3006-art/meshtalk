package com.meshtalk.app.transport

import android.util.Base64
import com.meshtalk.app.data.model.MeshEnvelope
import kotlinx.serialization.Serializable

/**
 * JSON-friendly mirror of MeshEnvelope (ByteArray fields aren't directly
 * kotlinx.serialization-JSON-friendly, so we base64-encode them here). Shared by every
 * transport so a message looks identical whether it travels over Bluetooth/WiFi or the
 * internet relay — the relay server forwards this shape byte-for-byte without parsing
 * ciphertext/nonce.
 */
@Serializable
data class WireEnvelope(
    val envelopeId: String,
    val chatId: String,
    val senderPeerId: String,
    val ciphertextB64: String,
    val nonceB64: String,
    val ratchetPublicKeyB64: String,
    val previousChainLength: Int,
    val messageNumber: Int,
    val ttl: Int,
    val timestamp: Long
)

fun MeshEnvelope.toWire() = WireEnvelope(
    envelopeId, chatId, senderPeerId,
    Base64.encodeToString(ciphertext, Base64.NO_WRAP),
    Base64.encodeToString(nonce, Base64.NO_WRAP),
    Base64.encodeToString(ratchetPublicKey, Base64.NO_WRAP),
    previousChainLength, messageNumber,
    ttl, timestamp
)

fun WireEnvelope.toEnvelope() = MeshEnvelope(
    envelopeId, chatId, senderPeerId,
    Base64.decode(ciphertextB64, Base64.NO_WRAP),
    Base64.decode(nonceB64, Base64.NO_WRAP),
    Base64.decode(ratchetPublicKeyB64, Base64.NO_WRAP),
    previousChainLength, messageNumber,
    ttl, timestamp
)
