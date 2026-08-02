package com.meshtalk.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meshtalk.app.attachments.VoiceRecorder
import com.meshtalk.app.data.model.TransportKind
import com.meshtalk.app.data.repository.ChatEntity
import com.meshtalk.app.data.repository.ChatRepository
import com.meshtalk.app.location.LocationProvider
import com.meshtalk.app.ui.screens.AddContactScreen
import com.meshtalk.app.ui.screens.ChatListItem
import com.meshtalk.app.ui.screens.ChatListScreen
import com.meshtalk.app.ui.screens.ChatScreen
import com.meshtalk.app.ui.theme.MeshTalkTheme
import kotlinx.coroutines.launch

/**
 * Runtime permissions MeshTalk needs to actually function:
 *  - Bluetooth (Android 12+ granular model) for the BT half of Nearby transport
 *  - Nearby WiFi devices for the WiFi Direct half
 *  - Location, still required by Nearby Connections on many OEMs for BLE scanning
 *  - Notifications, for the mesh-relay foreground service (Android 13+)
 */
private val requiredPermissions: Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.RECORD_AUDIO)
}.toTypedArray()

class MainActivity : ComponentActivity() {

    // Set right before launching the file picker, read back once the user has chosen a
    // file — the picker's callback fires later, outside any composable's scope, so it
    // needs to know which chat this attachment is destined for some other way.
    private var pendingFilePickChatId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Only start the relay service once we actually hold the transport permissions,
            // otherwise Nearby's startAdvertising/startDiscovery calls throw SecurityException.
            if (granted.values.all { it }) {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, android.content.Intent(this, com.meshtalk.app.mesh.MeshRelayService::class.java)
                )
            }
        }.launch(requiredPermissions)

        val app = application as MeshTalkApp
        val repo = app.repository

        val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val chatId = pendingFilePickChatId
            pendingFilePickChatId = null
            if (uri == null || chatId == null) return@registerForActivityResult

            lifecycleScope.launch {
                val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                if (bytes == null) {
                    android.widget.Toast.makeText(this@MainActivity, "Не удалось прочитать файл", android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                val fileName = queryDisplayName(uri) ?: "file"
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                try {
                    repo.sendFile(chatId, bytes, fileName, mimeType)
                } catch (e: ChatRepository.AttachmentTooLargeException) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Файл слишком большой (${e.actualSizeBytes / (1024 * 1024)}MB) — лимит ${ChatRepository.MAX_ATTACHMENT_BYTES / (1024 * 1024)}MB",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        setContent {
            MeshTalkTheme {
                val navController = rememberNavController()
                val chats by repo.observeChats().collectAsStateWithLifecycle(initialValue = emptyList())
                val peers by repo.observePeers().collectAsStateWithLifecycle(initialValue = emptyList())
                val scope = rememberCoroutineScope()
                val locationProvider = remember { LocationProvider(app) }

                NavHost(navController, startDestination = "chatList") {
                    composable("chatList") {
                        val listItems = chats.map { chat ->
                            ChatListItem(
                                chat = com.meshtalk.app.data.model.Chat(
                                    chatId = chat.chatId, title = chat.title, isGroup = chat.isGroup,
                                    memberPeerIds = chat.memberPeerIdsCsv.split(","), createdAt = chat.createdAt
                                ),
                                lastMessagePreview = "", // TODO: join latest message per chat once MessageDao gets a "latest" query
                                lastMessageTime = "",
                                unreadCount = 0,
                                activeTransport = TransportKind.WIFI_DIRECT
                            )
                        }
                        ChatListScreen(
                            chats = listItems,
                            onChatClick = { navController.navigate("chat/${it.chatId}") },
                            onNewChatClick = { navController.navigate("addContact") }
                        )
                    }
                    composable("addContact") {
                        AddContactScreen(
                            localPeerId = app.localPeerId,
                            localPublicKey = app.cryptoManager.getIdentityPublicKey(),
                            onScannedContact = { decoded ->
                                scope.launch {
                                    val chatId = repo.addScannedContactAndOpenChat(decoded)
                                    navController.navigate("chat/$chatId") {
                                        popUpTo("chatList")
                                    }
                                }
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable("chat/{chatId}") { backStackEntry ->
                        val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
                        val chat: ChatEntity? = chats.firstOrNull { it.chatId == chatId }
                        if (chat == null) {
                            // Can briefly happen right after createChat(): Room's Flow hasn't
                            // emitted the new row yet. Recomposes automatically once it does.
                            androidx.compose.material3.Text(
                                "Открываем чат…",
                                modifier = androidx.compose.ui.Modifier.padding(24.dp)
                            )
                            return@composable
                        }
                        val messages by repo.observeMessages(chatId).collectAsStateWithLifecycle(initialValue = emptyList())
                        var isRecording by remember(chatId) { mutableStateOf(false) }
                        var activeRecorder by remember(chatId) { mutableStateOf<VoiceRecorder?>(null) }

                        ChatScreen(
                            title = chat.title,
                            messages = messages,
                            localPeerId = app.localPeerId,
                            isRecording = isRecording,
                            onSendText = { text -> scope.launch { repo.sendText(chatId, text) } },
                            onSendLocation = {
                                scope.launch {
                                    if (!locationProvider.hasPermission()) {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Нужен доступ к геолокации — разреши его в настройках приложения",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                        return@launch
                                    }
                                    val point = locationProvider.getCurrentLocation()
                                    if (point == null) {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Не удалось определить местоположение — проверь, включена ли геолокация",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                        return@launch
                                    }
                                    repo.sendLocation(chatId, point.lat, point.lon)
                                }
                            },
                            onToggleRecording = {
                                if (isRecording) {
                                    val result = activeRecorder?.stop()
                                    activeRecorder = null
                                    isRecording = false
                                    if (result != null) {
                                        scope.launch {
                                            try {
                                                repo.sendVoice(chatId, result.file, result.durationMs, result.mimeType)
                                            } catch (e: ChatRepository.AttachmentTooLargeException) {
                                                android.widget.Toast.makeText(
                                                    this@MainActivity,
                                                    "Голосовое сообщение слишком большое — лимит ${ChatRepository.MAX_ATTACHMENT_BYTES / (1024 * 1024)}MB",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            } finally {
                                                result.file.delete() // repo already saved its own copy for the outgoing bubble
                                            }
                                        }
                                    }
                                } else {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                                        != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        android.widget.Toast.makeText(
                                            this@MainActivity,
                                            "Нужен доступ к микрофону — разреши его в настройках приложения",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        val recorder = VoiceRecorder(app)
                                        runCatching { recorder.start() }.onSuccess {
                                            activeRecorder = recorder
                                            isRecording = true
                                        }
                                    }
                                }
                            },
                            onPickFile = {
                                pendingFilePickChatId = chatId
                                filePickerLauncher.launch("*/*")
                            },
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    /** Looks up the original filename for a content:// URI (e.g. from the system file picker). */
    private fun queryDisplayName(uri: android.net.Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
