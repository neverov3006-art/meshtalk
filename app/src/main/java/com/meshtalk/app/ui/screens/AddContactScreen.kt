package com.meshtalk.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meshtalk.app.data.model.InviteCode
import com.meshtalk.app.ui.components.QrCodeGenerator
import com.meshtalk.app.ui.components.QrScannerView
import com.meshtalk.app.ui.theme.TelegramBlue

private enum class AddContactTab { MY_CODE, SCAN }

/**
 * Two ways to add a friend:
 *  - MY_CODE: shows this device's peerId+publicKey as a QR code for a friend to scan.
 *  - SCAN: opens the camera, decodes a friend's QR, and (once valid) calls back with
 *    their peerId/publicKey/displayName so the caller can save them + create a chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    localPeerId: String,
    localPublicKey: ByteArray,
    onScannedContact: (InviteCode.Decoded) -> Unit,
    onBack: () -> Unit
) {
    var tab by remember { mutableStateOf(AddContactTab.MY_CODE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Добавить друга") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", modifier = Modifier.padding(start = 12.dp)) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == AddContactTab.MY_CODE,
                    onClick = { tab = AddContactTab.MY_CODE },
                    text = { Text("Мой код") }
                )
                Tab(
                    selected = tab == AddContactTab.SCAN,
                    onClick = { tab = AddContactTab.SCAN },
                    text = { Text("Сканировать") }
                )
            }

            when (tab) {
                AddContactTab.MY_CODE -> MyCodeTab(localPeerId, localPublicKey)
                AddContactTab.SCAN -> ScanTab(onScannedContact)
            }
        }
    }
}

@Composable
private fun MyCodeTab(localPeerId: String, localPublicKey: ByteArray) {
    var displayName by remember { mutableStateOf("") }
    val content = remember(displayName) {
        InviteCode.encode(localPeerId, localPublicKey, displayName.ifBlank { "Друг" })
    }
    val qrBitmap = remember(content) { QrCodeGenerator.generate(content) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Покажи этот код другу — он отсканирует его во вкладке «Сканировать», и у вас появится закрытый зашифрованный чат.",
            textAlign = TextAlign.Center,
            color = Color.Gray,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Как тебя подписать другу") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        Card(shape = RoundedCornerShape(16.dp)) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "QR-код приглашения",
                modifier = Modifier.size(260.dp).padding(16.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Код действителен, пока приложение открыто на этом экране. Ключ шифрования привязан к этому устройству.",
            textAlign = TextAlign.Center,
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ScanTab(onScannedContact: (InviteCode.Decoded) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var alreadyHandled by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            QrScannerView(modifier = Modifier.fillMaxSize()) { rawValue ->
                if (alreadyHandled) return@QrScannerView
                val decoded = InviteCode.decode(rawValue)
                if (decoded != null) {
                    alreadyHandled = true
                    onScannedContact(decoded)
                } else {
                    errorMessage = "Это не приглашение MeshTalk"
                }
            }
            // Simple viewfinder frame overlay so it's clear where to point the camera.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .border(2.dp, TelegramBlue, RoundedCornerShape(16.dp))
            )
            errorMessage?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = TelegramBlue, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Нужен доступ к камере, чтобы сканировать код друга", textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Разрешить камеру")
                }
            }
        }
    }
}
