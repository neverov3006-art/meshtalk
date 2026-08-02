package com.meshtalk.app.attachments

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Decrypted attachment bytes (voice notes, files) are written here — app-private
 * storage, not shared storage — rather than kept in the Room DB as a blob column.
 * Room is fine for small metadata rows; a growing pile of multi-MB attachments is
 * better served by the filesystem, with just a path stored in MessageEntity.
 */
class AttachmentStorage(private val context: Context) {

    private val attachmentsDir: File by lazy {
        File(context.filesDir, "attachments").apply { mkdirs() }
    }

    /** Saves raw bytes under a fresh unique filename (keeping the original extension if present). */
    fun save(bytes: ByteArray, suggestedFileName: String): File {
        val extension = suggestedFileName.substringAfterLast('.', "")
        val safeName = UUID.randomUUID().toString() + if (extension.isNotBlank()) ".$extension" else ""
        val file = File(attachmentsDir, safeName)
        file.writeBytes(bytes)
        return file
    }

    fun read(path: String): ByteArray = File(path).readBytes()

    /** A content:// URI other apps (a media player, a document viewer) can open, via FileProvider. */
    fun contentUri(path: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
}
