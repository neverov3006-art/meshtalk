package com.meshtalk.app

import android.app.Application
import androidx.room.Room
import com.meshtalk.app.attachments.AttachmentStorage
import com.meshtalk.app.crypto.CryptoManager
import com.meshtalk.app.crypto.RatchetSessionManager
import com.meshtalk.app.data.repository.ChatRepository
import com.meshtalk.app.data.repository.MeshTalkDatabase
import com.meshtalk.app.mesh.MeshRouter
import com.meshtalk.app.transport.InternetTransport
import com.meshtalk.app.transport.NearbyTransport
import com.meshtalk.app.transport.PeerHandshakeListener
import java.util.UUID

class MeshTalkApp : Application() {

    val cryptoManager = CryptoManager()

    /** Stable per-install identifier used as this device's peer ID on the mesh. */
    val localPeerId: String by lazy {
        val prefs = getSharedPreferences("meshtalk", MODE_PRIVATE)
        prefs.getString("peer_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("peer_id", it).apply()
        }
    }

    val database: MeshTalkDatabase by lazy {
        Room.databaseBuilder(this, MeshTalkDatabase::class.java, "meshtalk.db")
            .fallbackToDestructiveMigration() // pre-release: no migration path needed yet, see README
            .build()
    }

    /** Repository owns the handshake callback so newly-met peers get their public key
     *  persisted immediately, before any message needs to be encrypted to/decrypted from them. */
    val attachmentStorage: AttachmentStorage by lazy { AttachmentStorage(this) }

    val repository: ChatRepository by lazy {
        ChatRepository(
            db = database,
            ratchet = RatchetSessionManager(database.ratchetSessionDao(), cryptoManager),
            meshRouter = meshRouter,
            attachments = attachmentStorage,
            localPeerId = localPeerId
        )
    }

    val meshRouter: MeshRouter by lazy {
        MeshRouter(
            transports = listOf(
                NearbyTransport(
                    context = this,
                    localPeerId = localPeerId,
                    localPublicKey = cryptoManager.getIdentityPublicKey(),
                    onPeerHandshake = PeerHandshakeListener { peerId, publicKey ->
                        repository.onPeerHandshake(peerId, publicKey)
                    }
                ),
                InternetTransport(
                    relayServerUrl = "wss://relay.example.com", // point at your deployed relay-server (see relay-server/README.md)
                    localPeerId = localPeerId
                )
            ),
            localPeerId = localPeerId
        )
    }

    override fun onCreate() {
        super.onCreate()
        cryptoManager.ensureIdentityKeyPair()
        repository.startListeningForIncoming()
    }
}
