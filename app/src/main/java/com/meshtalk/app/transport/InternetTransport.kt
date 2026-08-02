package com.meshtalk.app.transport

import android.util.Base64
import com.meshtalk.app.data.model.MeshEnvelope
import com.meshtalk.app.data.model.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Sends/receives envelopes through the MeshTalk relay server (see /relay-server) when
 * it's reachable. Same encrypted envelope format as the local mesh transports — the
 * server only ever sees peerId routing metadata and opaque ciphertext, never plaintext.
 *
 * This is what lets two friends who are NOT in Bluetooth/WiFi range still reach each
 * other, as long as both have any internet connection (WiFi or cellular).
 *
 * Streaming large envelopes: unlike NearbyTransport (which can hand large payloads to
 * Nearby Connections' own FILE transfer), a plain WebSocket has no built-in chunking —
 * one huge text frame is the only primitive. So for ciphertext above
 * [CHUNK_THRESHOLD_BYTES] we split it ourselves into a sequence of small ENVELOPE_CHUNK
 * frames and reassemble them on the receiving end. The relay server doesn't need to
 * know or care about any of this: it just forwards whatever frame carries a
 * `targetPeerId` to that peer, chunk or not (see server.js's generic-forwarding logic).
 *
 * Note: we deliberately avoid kotlinx.serialization's polymorphic sealed-class
 * machinery for the wire frames here — the relay server (a plain Node script) expects
 * one flat "type" string field per frame, and hand-rolling that keeps the two sides
 * trivially easy to keep in sync without fighting Kotlin's class-discriminator format.
 */
class InternetTransport(
    private val relayServerUrl: String, // e.g. "wss://relay.yourdomain.com" (see relay-server/README.md)
    private val localPeerId: String
) : Transport {

    companion object {
        /** Ciphertext at or below this size goes as one ENVELOPE frame; above it, chunked. */
        private const val CHUNK_THRESHOLD_BYTES = 48 * 1024 // 48 KB raw (before base64 inflation)
        private const val CHUNK_SIZE_BYTES = 48 * 1024
        /** Abandon a partially-received chunk set after this long — a peer that vanishes
         *  mid-transfer shouldn't leak memory into an ever-growing reassembly buffer. */
        private const val CHUNK_BUFFER_TIMEOUT_MS = 2 * 60_000L
    }

    override val kind: TransportKind = TransportKind.INTERNET
    override var isAvailable: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().build()

    private var webSocket: WebSocket? = null
    private var stopped = false
    private var reconnectAttempt = 0

    private val incomingFlow = MutableSharedFlow<MeshEnvelope>(extraBufferCapacity = 64)

    /** In-progress chunk reassembly, keyed by envelopeId. */
    private class ChunkBuffer(val totalChunks: Int, val meta: ChunkFrame) {
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        var receivedCount = 0
        val receivedAtMs = System.currentTimeMillis()
    }
    private val chunkBuffers = ConcurrentHashMap<String, ChunkBuffer>()

    override suspend fun start() {
        stopped = false
        connect()
    }

    private fun connect() {
        val request = Request.Builder().url(relayServerUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isAvailable = true
                reconnectAttempt = 0
                webSocket.send(json.encodeToString(RegisterFrame(peerId = localPeerId)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = runCatching { json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
                when (type) {
                    "ENVELOPE" -> runCatching { json.decodeFromString<IncomingEnvelopeFrame>(text) }
                        .onSuccess { incomingFlow.tryEmit(it.envelope.toEnvelope()) }
                    "ENVELOPE_CHUNK" -> runCatching { json.decodeFromString<ChunkFrame>(text) }
                        .onSuccess { handleChunk(it) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isAvailable = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isAvailable = false
                scheduleReconnect()
            }
        })
    }

    private fun handleChunk(frame: ChunkFrame) {
        cleanupStaleBuffers()

        val buffer = chunkBuffers.getOrPut(frame.envelopeId) { ChunkBuffer(frame.totalChunks, frame) }
        val index = frame.chunkIndex
        if (index !in buffer.chunks.indices || buffer.chunks[index] != null) return // out of range or duplicate

        buffer.chunks[index] = Base64.decode(frame.chunkDataB64, Base64.NO_WRAP)
        buffer.receivedCount++

        if (buffer.receivedCount == buffer.totalChunks) {
            chunkBuffers.remove(frame.envelopeId)
            val totalSize = buffer.chunks.sumOf { it?.size ?: 0 }
            val fullCiphertext = ByteArray(totalSize)
            var offset = 0
            for (chunk in buffer.chunks) {
                if (chunk == null) continue
                chunk.copyInto(fullCiphertext, offset)
                offset += chunk.size
            }
            val meta = buffer.meta
            incomingFlow.tryEmit(
                MeshEnvelope(
                    envelopeId = meta.envelopeId,
                    chatId = meta.chatId,
                    senderPeerId = meta.senderPeerId,
                    ciphertext = fullCiphertext,
                    nonce = Base64.decode(meta.nonceB64, Base64.NO_WRAP),
                    ratchetPublicKey = Base64.decode(meta.ratchetPublicKeyB64, Base64.NO_WRAP),
                    previousChainLength = meta.previousChainLength,
                    messageNumber = meta.messageNumber,
                    ttl = meta.ttl,
                    timestamp = meta.timestamp
                )
            )
        }
    }

    private fun cleanupStaleBuffers() {
        val cutoff = System.currentTimeMillis() - CHUNK_BUFFER_TIMEOUT_MS
        chunkBuffers.entries.removeIf { it.value.receivedAtMs < cutoff }
    }

    private fun scheduleReconnect() {
        if (stopped) return
        reconnectAttempt++
        // Exponential backoff capped at 30s so a dead server doesn't spin the radio/CPU.
        val delayMs = min(30_000L, 1_000L * (1L shl min(reconnectAttempt, 5)))
        scope.launch {
            delay(delayMs)
            if (!stopped) connect()
        }
    }

    override suspend fun stop() {
        stopped = true
        webSocket?.close(1000, "client stopping")
        webSocket = null
        isAvailable = false
        chunkBuffers.clear()
    }

    override suspend fun send(envelope: MeshEnvelope, targetPeerId: String?) {
        // The relay only makes sense for addressed 1:1 delivery — it has no concept of
        // "flood to everyone", so a null targetPeerId (mesh-relay rebroadcast) is a no-op
        // here; flooding only makes sense on the local Bluetooth/WiFi mesh.
        val target = targetPeerId ?: return
        val socket = webSocket ?: return
        if (!isAvailable) return

        if (envelope.ciphertext.size <= CHUNK_THRESHOLD_BYTES) {
            socket.send(json.encodeToString(OutgoingEnvelopeFrame(targetPeerId = target, envelope = envelope.toWire())))
        } else {
            sendChunked(socket, envelope, target)
        }
    }

    private fun sendChunked(socket: WebSocket, envelope: MeshEnvelope, target: String) {
        val ciphertext = envelope.ciphertext
        val totalChunks = (ciphertext.size + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES

        for (index in 0 until totalChunks) {
            val start = index * CHUNK_SIZE_BYTES
            val end = min(start + CHUNK_SIZE_BYTES, ciphertext.size)
            val chunkBytes = ciphertext.copyOfRange(start, end)

            val frame = ChunkFrame(
                targetPeerId = target,
                envelopeId = envelope.envelopeId,
                chunkIndex = index,
                totalChunks = totalChunks,
                chunkDataB64 = Base64.encodeToString(chunkBytes, Base64.NO_WRAP),
                chatId = envelope.chatId,
                senderPeerId = envelope.senderPeerId,
                nonceB64 = Base64.encodeToString(envelope.nonce, Base64.NO_WRAP),
                ratchetPublicKeyB64 = Base64.encodeToString(envelope.ratchetPublicKey, Base64.NO_WRAP),
                previousChainLength = envelope.previousChainLength,
                messageNumber = envelope.messageNumber,
                ttl = envelope.ttl,
                timestamp = envelope.timestamp
            )
            socket.send(json.encodeToString(frame))
        }
    }

    override fun incoming(): Flow<MeshEnvelope> = callbackFlow {
        val job = launch { incomingFlow.collect { trySend(it) } }
        awaitClose { job.cancel() }
    }
}

// --- Flat frames exchanged with the relay server (mirrors relay-server/server.js) ---

@Serializable
private data class RegisterFrame(val type: String = "REGISTER", val peerId: String)

@Serializable
private data class OutgoingEnvelopeFrame(val type: String = "ENVELOPE", val targetPeerId: String, val envelope: WireEnvelope)

@Serializable
private data class IncomingEnvelopeFrame(val type: String = "ENVELOPE", val envelope: WireEnvelope)

/**
 * One chunk of a large envelope's ciphertext. chatId/senderPeerId/nonceB64/etc. are
 * repeated on every chunk (a little wasteful) rather than only on chunk 0 — trivially
 * simple to reassemble and reason about, at the cost of a little redundant metadata.
 */
@Serializable
private data class ChunkFrame(
    val type: String = "ENVELOPE_CHUNK",
    val targetPeerId: String? = null, // set on outgoing; absent/ignored on incoming
    val envelopeId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkDataB64: String,
    val chatId: String,
    val senderPeerId: String,
    val nonceB64: String,
    val ratchetPublicKeyB64: String,
    val previousChainLength: Int,
    val messageNumber: Int,
    val ttl: Int,
    val timestamp: Long
)
