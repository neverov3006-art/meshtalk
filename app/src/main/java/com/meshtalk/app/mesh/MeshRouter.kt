package com.meshtalk.app.mesh

import com.meshtalk.app.data.model.MeshEnvelope
import com.meshtalk.app.transport.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Central mesh logic, transport-agnostic. Talks to N transports (Bluetooth/WiFi via
 * NearbyTransport, Internet via InternetTransport, more can be added later) and:
 *
 *  1. Sends: tries transports in priority order (direct internet path first if the
 *     recipient is reachable that way, otherwise floods over the local mesh so
 *     intermediate phones can relay it).
 *  2. Receives: for every inbound envelope, hands it to the app (decrypt + store) if
 *     it's for us, AND relays it onward to other transports/peers if TTL > 0 and we
 *     haven't seen this envelopeId before (flood-with-dedup, same idea as bitchat).
 *
 * Default TTL is kept small (6) since each hop is a phone-to-phone jump; that's plenty
 * to cross a room, building, or a loose chain of people at an event without messages
 * looping forever.
 */
class MeshRouter(
    private val transports: List<Transport>,
    private val localPeerId: String,
    private val defaultTtl: Int = 6
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    // Bounded recent-envelope cache to prevent relay loops/duplicate delivery.
    // (In a fuller implementation this should be persisted, size-capped, and time-evicted.)
    private val seenEnvelopeIds = Collections.newSetFromMap(
        Collections.synchronizedMap(LinkedHashMap<String, Boolean>(1024))
    )

    private val _deliveredForUs = MutableSharedFlow<MeshEnvelope>(extraBufferCapacity = 64)
    /** Envelopes addressed to (or in a group chat including) this device — feed these to CryptoManager + repository. */
    val deliveredForUs: SharedFlow<MeshEnvelope> = _deliveredForUs

    fun start() {
        transports.forEach { transport ->
            scope.launch { transport.start() }
            scope.launch {
                transport.incoming().collect { envelope -> onEnvelopeReceived(envelope, sourceTransport = transport) }
            }
        }
    }

    fun stop() {
        transports.forEach { t -> scope.launch { t.stop() } }
    }

    /** Called by the app layer to send an already-encrypted envelope, e.g. from ChatViewModel. */
    suspend fun sendEnvelope(envelope: MeshEnvelope, targetPeerId: String?) {
        // Prefer a transport that's actually available right now; if several are
        // (e.g. both Nearby and Internet), send over all of them — cheap redundancy
        // that meaningfully improves delivery odds and costs little for small text payloads.
        val available = transports.filter { it.isAvailable }
        if (available.isEmpty()) {
            // Nothing reachable yet — the caller (repository) is expected to keep this
            // envelope queued and retry on transport availability change / WorkManager tick.
            return
        }
        available.forEach { it.send(envelope, targetPeerId) }
        seenEnvelopeIds += envelope.envelopeId
    }

    private suspend fun onEnvelopeReceived(envelope: MeshEnvelope, sourceTransport: Transport) {
        if (!seenEnvelopeIds.add(envelope.envelopeId)) return // already handled this one, avoid loops

        // Always surface to the app layer; CryptoManager will determine (by trying to
        // decrypt with keys for chats we're a member of) whether this message is actually for us.
        _deliveredForUs.tryEmit(envelope)

        // Relay onward on every OTHER transport if it still has hops left.
        if (envelope.ttl > 0) {
            val relayed = envelope.copy(ttl = envelope.ttl - 1)
            transports.filter { it !== sourceTransport && it.isAvailable }
                .forEach { it.send(relayed, targetPeerId = null) }
        }
    }

    fun newEnvelopeTtl(): Int = defaultTtl
}
