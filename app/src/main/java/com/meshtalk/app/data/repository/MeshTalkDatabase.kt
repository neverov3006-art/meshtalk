package com.meshtalk.app.data.repository

import androidx.room.Database
import androidx.room.RoomDatabase
import com.meshtalk.app.crypto.RatchetSessionDao
import com.meshtalk.app.crypto.RatchetSessionEntity

@Database(
    entities = [
        PeerEntity::class, ChatEntity::class, MessageEntity::class,
        PendingEnvelopeEntity::class, RatchetSessionEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class MeshTalkDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun pendingEnvelopeDao(): PendingEnvelopeDao
    abstract fun ratchetSessionDao(): RatchetSessionDao
}
