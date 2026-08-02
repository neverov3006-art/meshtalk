package com.meshtalk.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshtalk.app.data.model.Chat
import com.meshtalk.app.data.model.TransportKind
import com.meshtalk.app.ui.theme.TelegramBlue

data class ChatListItem(
    val chat: Chat,
    val lastMessagePreview: String,
    val lastMessageTime: String,
    val unreadCount: Int,
    val activeTransport: TransportKind?
)

@Composable
fun ChatListScreen(
    chats: List<ChatListItem>,
    onChatClick: (Chat) -> Unit,
    onNewChatClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MeshTalk", fontWeight = FontWeight.Bold) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewChatClick, containerColor = TelegramBlue) {
                Icon(Icons.Filled.Add, contentDescription = "New chat", tint = Color.White)
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(chats) { item ->
                ChatRow(item, onClick = { onChatClick(item.chat) })
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatListItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(TelegramBlue),
            contentAlignment = Alignment.Center
        ) {
            Text(
                item.chat.title.take(1).uppercase(),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.chat.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                TransportBadge(item.activeTransport)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.lastMessagePreview,
                color = Color.Gray,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(item.lastMessageTime, fontSize = 12.sp, color = Color.Gray)
            if (item.unreadCount > 0) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier.size(20.dp).clip(CircleShape).background(TelegramBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item.unreadCount.toString(), color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Small icon showing which physical link this chat is currently reachable over. */
@Composable
private fun TransportBadge(transport: TransportKind?) {
    val icon = when (transport) {
        TransportKind.BLUETOOTH -> Icons.Filled.Bluetooth
        TransportKind.WIFI_DIRECT -> Icons.Filled.Wifi
        TransportKind.INTERNET -> Icons.Filled.Language
        else -> return
    }
    Icon(icon, contentDescription = transport?.name, tint = Color.Gray, modifier = Modifier.size(14.dp))
}
