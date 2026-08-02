package com.meshtalk.app.transport

import com.meshtalk.app.data.model.MeshEnvelope
import com.meshtalk.app.data.model.TransportKind
import kotlinx.coroutines.flow.Flow

/**
 * Common contract every physical transport (Bluetooth/WiFi Nearby, plain Internet)
 * implements. The mesh router (see mesh/MeshRouter.kt) talks only to this interface,
 * so it doesn't care whether a message physically left via Bluetooth, WiFi Direct,
 * or a relay server over the internet.
 */
interface Transport {
    val kind: TransportKind

    /** True once the transport is actively able to send (e.g. peer connected, socket open). */
    val isAvailable: Boolean

    /** Starts discovery/advertising or connects to the backend, depending on transport. */
    suspend fun start()

    suspend fun stop()

    /** Best-effort broadcast/send of an already-encrypted envelope to directly reachable peers. */
    suspend fun send(envelope: MeshEnvelope, targetPeerId: String?)

    /** Stream of envelopes received from this transport, handed to the mesh router for relay/delivery. */
    fun incoming(): Flow<MeshEnvelope>
}
