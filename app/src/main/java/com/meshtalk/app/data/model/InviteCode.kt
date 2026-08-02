package com.meshtalk.app.data.model

import android.util.Base64

/**
 * The content actually encoded into the QR code. Kept as a simple custom URI scheme
 * (not a generic JSON blob) so it's compact — QR codes get harder to scan reliably as
 * payload size grows, and this is scanned by a phone camera, often in low light.
 *
 * Format: meshtalk://invite?peerId=<id>&pk=<base64url-no-wrap publicKey>&name=<displayName>
 */
object InviteCode {

    fun encode(peerId: String, publicKey: ByteArray, displayName: String): String {
        val pkB64 = Base64.encodeToString(publicKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val nameEncoded = java.net.URLEncoder.encode(displayName, "UTF-8")
        return "meshtalk://invite?peerId=$peerId&pk=$pkB64&name=$nameEncoded"
    }

    data class Decoded(val peerId: String, val publicKey: ByteArray, val displayName: String)

    /** Returns null if the scanned QR content isn't a valid MeshTalk invite. */
    fun decode(raw: String): Decoded? {
        if (!raw.startsWith("meshtalk://invite")) return null
        return runCatching {
            val query = raw.substringAfter("?", "")
            val params = query.split("&").associate { part ->
                val (key, value) = part.split("=", limit = 2)
                key to value
            }
            val peerId = params["peerId"] ?: return null
            val pkB64 = params["pk"] ?: return null
            val name = params["name"]?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: peerId.take(8)
            val publicKey = Base64.decode(pkB64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            Decoded(peerId, publicKey, name)
        }.getOrNull()
    }
}
