package com.meshtalk.app.transport

import android.content.Context
import android.util.Base64
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.meshtalk.app.data.model.MeshEnvelope
import com.meshtalk.app.data.model.TransportKind
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Fired once per newly-connected peer, after the HELLO handshake completes. Lets the
 *  repository layer save the peer's public key and derive a per-chat encryption key. */
fun interface PeerHandshakeListener {
    fun onPeerHandshake(peerId: String, publicKey: ByteArray)
}

/**
 * Covers Bluetooth + WiFi Direct in one implementation via Nearby Connections
 * (STRATEGY_P2P_CLUSTER auto-picks BT/WiFi Direct depending on what's available).
 *
 * On every new connection we run a small handshake before any chat traffic:
 * each side sends a HELLO{peerId, publicKey}. This is what lets `send(envelope, targetPeerId)`
 * actually address a specific person instead of just flooding everyone, and lets the
 * app layer learn+store a new contact's public key automatically the first time two
 * phones connect (e.g. during "create chat" pairing).
 *
 * Streaming large envelopes (voice notes, files): small envelopes (plain text, location)
 * go over a BYTES payload exactly as before — cheap and immediate. Anything whose
 * ciphertext exceeds [LARGE_PAYLOAD_THRESHOLD_BYTES] instead goes out as a Nearby FILE
 * payload: we write the ciphertext to a temp file and hand it to `Payload.fromFile(...)`,
 * letting Nearby Connections handle chunking, flow control, and retry over
 * Bluetooth/WiFi internally rather than us hand-rolling it. A small BYTES
 * "LargeEnvelopeMeta" message carries everything else (chatId, nonce, ratchet header,
 * etc.) plus the FILE payload's id, so the receiver can correlate the two once the file
 * transfer completes and reassemble a normal MeshEnvelope.
 */
class NearbyTransport(
    private val context: Context,
    private val localPeerId: String,
    private val localPublicKey: ByteArray,
    private val onPeerHandshake: PeerHandshakeListener
) : Transport {

    companion object {
        /** Ciphertext at or below this size goes as a small BYTES payload; above it, a streamed FILE payload. */
        private const val LARGE_PAYLOAD_THRESHOLD_BYTES = 32 * 1024 // 32 KB
    }

    override val kind: TransportKind = TransportKind.WIFI_DIRECT // reported kind for UI; actual medium picked by Nearby per-connection
    override var isAvailable: Boolean = false
        private set

    private val connectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private val json = Json { ignoreUnknownKeys = true }

    // endpointId is Nearby's transient connection handle; peerId is our stable app-level identity.
    // Both directions are needed: outgoing send(targetPeerId) -> endpointId, incoming payload -> peerId.
    private val endpointToPeer = ConcurrentHashMap<String, String>()
    private val peerToEndpoint = ConcurrentHashMap<String, String>()

    // Correlates a streamed FILE payload with its envelope metadata — whichever arrives
    // second (usually the file, since it takes longer to transfer) triggers reassembly.
    private val pendingLargeMeta = ConcurrentHashMap<Long, WireMessage.LargeEnvelopeMeta>()
    private val pendingLargeFiles = ConcurrentHashMap<Long, File>()
    // Incoming FILE payloads we've seen via onPayloadReceived but whose transfer hasn't
    // reported SUCCESS yet — kept so onPayloadTransferUpdate can pull the finished file out.
    private val incomingFilePayloads = ConcurrentHashMap<Long, Payload>()
    // Our own outgoing temp files, keyed by payload id, cleaned up once Nearby confirms delivery.
    private val outgoingTempFiles = ConcurrentHashMap<Long, File>()

    private val serviceId = "com.meshtalk.app.mesh"
    private val strategy = Strategy.P2P_CLUSTER

    override suspend fun start() {
        val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    isAvailable = true
                    // Kick off the handshake immediately so we learn the peer's identity/key
                    // before any real chat envelopes need to be routed to them.
                    sendRaw(endpointId, WireMessage.Hello(localPeerId, Base64.encodeToString(localPublicKey, Base64.NO_WRAP)))
                }
            }

            override fun onDisconnected(endpointId: String) {
                endpointToPeer.remove(endpointId)?.let { peerToEndpoint.remove(it) }
                isAvailable = endpointToPeer.isNotEmpty()
            }
        }

        connectionsClient.startAdvertising(
            localPeerId, serviceId, connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        )
        connectionsClient.startDiscovery(
            serviceId,
            object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    connectionsClient.requestConnection(localPeerId, endpointId, connectionLifecycleCallback)
                }
                override fun onEndpointLost(endpointId: String) {
                    endpointToPeer.remove(endpointId)?.let { peerToEndpoint.remove(it) }
                }
            },
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        )
    }

    override suspend fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        endpointToPeer.clear()
        peerToEndpoint.clear()
        pendingLargeMeta.clear()
        pendingLargeFiles.clear()
        incomingFilePayloads.clear()
        outgoingTempFiles.clear()
        isAvailable = false
    }

    override suspend fun send(envelope: MeshEnvelope, targetPeerId: String?) {
        val targets = if (targetPeerId == null) {
            endpointToPeer.keys.toList() // flood: mesh relay hop or group chat
        } else {
            peerToEndpoint[targetPeerId]?.let { listOf(it) }
                ?: endpointToPeer.keys.toList() // not a direct neighbor — fall back to flood, mesh relay gets it there
        }

        if (envelope.ciphertext.size <= LARGE_PAYLOAD_THRESHOLD_BYTES) {
            val message = WireMessage.Envelope(envelope.toWire())
            targets.forEach { endpointId -> sendRaw(endpointId, message) }
        } else {
            targets.forEach { endpointId -> sendLarge(endpointId, envelope) }
        }
    }

    /** Streams a large envelope's ciphertext as a Nearby FILE payload, with a small BYTES header describing it. */
    private fun sendLarge(endpointId: String, envelope: MeshEnvelope) {
        val tempFile = File(context.cacheDir, "outgoing_${UUID.randomUUID()}.bin")
        tempFile.writeBytes(envelope.ciphertext)
        val filePayload = Payload.fromFile(tempFile)
        outgoingTempFiles[filePayload.id] = tempFile

        val meta = WireMessage.LargeEnvelopeMeta(
            payloadId = filePayload.id,
            envelopeId = envelope.envelopeId,
            chatId = envelope.chatId,
            senderPeerId = envelope.senderPeerId,
            nonceB64 = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
            ratchetPublicKeyB64 = Base64.encodeToString(envelope.ratchetPublicKey, Base64.NO_WRAP),
            previousChainLength = envelope.previousChainLength,
            messageNumber = envelope.messageNumber,
            ttl = envelope.ttl,
            timestamp = envelope.timestamp
        )
        // Metadata first: it's tiny and arrives almost immediately, so the receiver
        // already knows what's coming by the time the (slower) file transfer finishes.
        sendRaw(endpointId, meta)
        connectionsClient.sendPayload(endpointId, filePayload)
    }

    private fun sendRaw(endpointId: String, message: WireMessage) {
        val bytes = json.encodeToString(message).toByteArray()
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    private val incomingEnvelopes = MutableSharedFlow<MeshEnvelope>(extraBufferCapacity = 64)

    /** Once both the metadata and the file bytes for a given payloadId are in hand, emit the reassembled envelope. */
    private fun tryAssembleLarge(payloadId: Long) {
        val meta = pendingLargeMeta[payloadId] ?: return
        val file = pendingLargeFiles[payloadId] ?: return
        pendingLargeMeta.remove(payloadId)
        pendingLargeFiles.remove(payloadId)

        val ciphertext = runCatching { file.readBytes() }.getOrNull()
        runCatching { file.delete() }
        if (ciphertext == null) return

        incomingEnvelopes.tryEmit(
            MeshEnvelope(
                envelopeId = meta.envelopeId,
                chatId = meta.chatId,
                senderPeerId = meta.senderPeerId,
                ciphertext = ciphertext,
                nonce = Base64.decode(meta.nonceB64, Base64.NO_WRAP),
                ratchetPublicKey = Base64.decode(meta.ratchetPublicKeyB64, Base64.NO_WRAP),
                previousChainLength = meta.previousChainLength,
                messageNumber = meta.messageNumber,
                ttl = meta.ttl,
                timestamp = meta.timestamp
            )
        )
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    runCatching { json.decodeFromString<WireMessage>(String(bytes)) }
                        .onSuccess { message ->
                            when (message) {
                                is WireMessage.Hello -> {
                                    endpointToPeer[endpointId] = message.peerId
                                    peerToEndpoint[message.peerId] = endpointId
                                    val keyBytes = Base64.decode(message.publicKeyB64, Base64.NO_WRAP)
                                    onPeerHandshake.onPeerHandshake(message.peerId, keyBytes)
                                }
                                is WireMessage.Envelope -> incomingEnvelopes.tryEmit(message.envelope.toEnvelope())
                                is WireMessage.LargeEnvelopeMeta -> {
                                    pendingLargeMeta[message.payloadId] = message
                                    tryAssembleLarge(message.payloadId)
                                }
                            }
                        }
                }
                Payload.Type.FILE -> {
                    // Keep the Payload reference; its bytes aren't ready until
                    // onPayloadTransferUpdate reports SUCCESS for this payload's id, below.
                    incomingFilePayloads[payload.id] = payload
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status != PayloadTransferUpdate.Status.SUCCESS) return

            // Case 1: one of our own outgoing sends (BYTES or FILE) finished — clean up the temp file, if any.
            outgoingTempFiles.remove(update.payloadId)?.let { runCatching { it.delete() } }

            // Case 2: an incoming FILE payload finished transferring — pull out the java.io.File
            // and try to pair it with metadata (which usually arrived already, being tiny).
            val incomingPayload = incomingFilePayloads.remove(update.payloadId) ?: return
            val file = incomingPayload.asFile()?.asJavaFile() ?: return
            pendingLargeFiles[update.payloadId] = file
            tryAssembleLarge(update.payloadId)
        }
    }

    override fun incoming(): Flow<MeshEnvelope> = callbackFlow {
        val job = GlobalScope.launch { incomingEnvelopes.collect { trySend(it) } }
        awaitClose { job.cancel() }
    }
}

// --- Wire protocol: handshake / small envelopes / large-envelope metadata, same channel ---

@Serializable
private sealed class WireMessage {
    @Serializable
    data class Hello(val peerId: String, val publicKeyB64: String) : WireMessage()

    @Serializable
    data class Envelope(val envelope: WireEnvelope) : WireMessage()

    /** Describes a MeshEnvelope whose ciphertext is being streamed separately as a Nearby FILE payload. */
    @Serializable
    data class LargeEnvelopeMeta(
        val payloadId: Long,
        val envelopeId: String,
        val chatId: String,
        val senderPeerId: String,
        val nonceB64: String,
        val ratchetPublicKeyB64: String,
        val previousChainLength: Int,
        val messageNumber: Int,
        val ttl: Int,
        val timestamp: Long
    ) : WireMessage()
}
