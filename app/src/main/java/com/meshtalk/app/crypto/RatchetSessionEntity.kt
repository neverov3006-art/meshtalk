package com.meshtalk.app.crypto

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Persisted Double Ratchet session state for one (chatId, remotePeerId) pair.
 * Group chats keep one row per member (see RatchetSessionManager) since the ratchet
 * is fundamentally a pairwise construct — there's no single "group root key".
 *
 * Field names follow the Signal Double Ratchet spec's variable names (rootKey, CKs/CKr
 * = sending/receiving chain keys, Ns/Nr = message counters, PN = previous chain length)
 * so this can be cross-checked against the spec directly:
 * https://signal.org/docs/specifications/doubleratchet/
 */
@Entity(tableName = "ratchet_sessions", primaryKeys = ["chatId", "remotePeerId"])
data class RatchetSessionEntity(
    val chatId: String,
    val remotePeerId: String,

    val rootKey: ByteArray,

    val dhSelfPrivate: ByteArray,   // our current ratchet private key (PKCS#8 X25519)
    val dhSelfPublic: ByteArray,    // our current ratchet public key (X.509 X25519)
    val dhRemotePublic: ByteArray?, // their last-known ratchet public key, null before first receive

    val chainKeySend: ByteArray?,
    val chainKeyRecv: ByteArray?,

    val messageNumberSend: Int,
    val messageNumberRecv: Int,
    val previousChainLength: Int,

    // Serialized (JSON) map of skipped message keys we haven't consumed yet, keyed by
    // "dhPublicKeyB64:messageNumber" -> keyB64. Needed to decrypt out-of-order messages
    // that arrive after the ratchet has already stepped forward. Kept small/bounded by
    // RatchetSessionManager.
    val skippedKeysJson: String
)

@Dao
interface RatchetSessionDao {
    @Query("SELECT * FROM ratchet_sessions WHERE chatId = :chatId AND remotePeerId = :remotePeerId")
    suspend fun get(chatId: String, remotePeerId: String): RatchetSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: RatchetSessionEntity)
}
