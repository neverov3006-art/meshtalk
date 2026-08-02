package com.meshtalk.app.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshtalk.app.data.model.DeliveryState
import com.meshtalk.app.data.model.Message
import com.meshtalk.app.data.model.MessageType
import com.meshtalk.app.ui.theme.BubbleIncoming
import com.meshtalk.app.ui.theme.BubbleOutgoing
import com.meshtalk.app.ui.theme.TelegramBlue
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    title: String,
    messages: List<Message>,
    localPeerId: String,
    isRecording: Boolean,
    onSendText: (String) -> Unit,
    onSendLocation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPickFile: () -> Unit,
    onBack: () -> Unit
) {
    var draft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Text("encrypted chat", fontSize = 12.sp, color = Color.Gray)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 20.sp) }
                }
            )
        },
        bottomBar = {
            MessageInputBar(
                draft = draft,
                isRecording = isRecording,
                onDraftChange = { draft = it },
                onSend = {
                    if (draft.isNotBlank()) {
                        onSendText(draft)
                        draft = ""
                    }
                },
                onSendLocation = onSendLocation,
                onToggleRecording = onToggleRecording,
                onPickFile = onPickFile
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            items(messages.reversed()) { message ->
                MessageBubble(message, isOutgoing = message.senderPeerId == localPeerId)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, isOutgoing: Boolean) {
    val bubbleColor = if (isOutgoing) BubbleOutgoing else BubbleIncoming
    val alignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            when (message.type) {
                MessageType.TEXT -> Text(message.text.orEmpty(), fontSize = 15.sp)
                MessageType.LOCATION -> LocationBubbleContent(message)
                MessageType.AUDIO -> AudioBubbleContent(message)
                MessageType.FILE -> FileBubbleContent(message)
                MessageType.SYSTEM -> Text(message.text.orEmpty(), fontSize = 13.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(message.timestamp), fontSize = 11.sp, color = Color.Gray)
                if (isOutgoing) {
                    Spacer(Modifier.width(4.dp))
                    DeliveryTick(message.state)
                }
            }
        }
    }
}

@Composable
private fun LocationBubbleContent(message: Message) {
    val context = LocalContext.current
    val canOpenInMaps = message.lat != null && message.lon != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (canOpenInMaps) {
            Modifier.clickable {
                val uri = android.net.Uri.parse("geo:${message.lat},${message.lon}?q=${message.lat},${message.lon}")
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                runCatching { context.startActivity(intent) }
            }
        } else Modifier
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = TelegramBlue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Column {
            Text("Location shared", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (message.lat != null && message.lon != null) {
                Text(
                    "%.5f, %.5f".format(message.lat, message.lon),
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

/** Voice-message bubble: a play/pause button backed by a MediaPlayer over the decrypted local file. */
@Composable
private fun AudioBubbleContent(message: Message) {
    val path = message.attachmentPath
    var isPlaying by remember(message.id) { mutableStateOf(false) }
    var mediaPlayer by remember(message.id) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(message.id) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (path == null) return@IconButton
                if (isPlaying) {
                    mediaPlayer?.pause()
                    isPlaying = false
                } else {
                    val player = mediaPlayer ?: MediaPlayer().apply {
                        setDataSource(path)
                        prepare()
                        setOnCompletionListener { isPlaying = false }
                    }.also { mediaPlayer = it }
                    player.start()
                    isPlaying = true
                }
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = TelegramBlue
            )
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text("Voice message", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            val durationMs = message.attachmentDurationMs ?: 0
            Text(formatDuration(durationMs), fontSize = 12.sp, color = Color.Gray)
        }
    }
}

/** File-message bubble: filename + human-readable size, tap opens it in whatever app handles that mime type. */
@Composable
private fun FileBubbleContent(message: Message) {
    val context = LocalContext.current
    val path = message.attachmentPath
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (path != null) {
            Modifier.clickable {
                runCatching {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", java.io.File(path)
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, message.attachmentMimeType ?: "*/*")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                }
            }
        } else Modifier
    ) {
        Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = TelegramBlue, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(message.attachmentFileName ?: "File", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(formatFileSize(message.attachmentSizeBytes ?: 0L), fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun DeliveryTick(state: DeliveryState) {
    val (symbol, color) = when (state) {
        DeliveryState.SENDING -> "🕓" to Color.Gray
        DeliveryState.RELAYED -> "↻" to Color.Gray
        DeliveryState.DELIVERED -> "✓✓" to TelegramBlue
        DeliveryState.FAILED -> "!" to Color.Red
    }
    Text(symbol, fontSize = 11.sp, color = color)
}

@Composable
private fun MessageInputBar(
    draft: String,
    isRecording: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendLocation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPickFile: () -> Unit
) {
    Surface(shadowElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSendLocation, enabled = !isRecording) {
                Icon(Icons.Filled.LocationOn, contentDescription = "Send location", tint = TelegramBlue)
            }
            IconButton(onClick = onPickFile, enabled = !isRecording) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach file", tint = TelegramBlue)
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                enabled = !isRecording,
                placeholder = { Text(if (isRecording) "Recording…" else "Message") },
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(4.dp))
            if (draft.isBlank()) {
                // No text typed: this button is the mic (tap to start/stop a voice note).
                IconButton(onClick = onToggleRecording) {
                    Box(
                        modifier = if (isRecording) {
                            Modifier.size(32.dp).clip(CircleShape).background(Color.Red.copy(alpha = 0.15f))
                        } else Modifier.size(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isRecording) "Stop recording" else "Record voice message",
                            tint = if (isRecording) Color.Red else TelegramBlue
                        )
                    }
                }
            } else {
                IconButton(onClick = onSend) {
                    Icon(Icons.Filled.Send, contentDescription = "Send", tint = TelegramBlue)
                }
            }
        }
    }
}

private fun formatTime(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))

private fun formatDuration(durationMs: Int): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
