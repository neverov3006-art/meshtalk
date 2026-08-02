package com.meshtalk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Telegram-esque palette: signature blue accent, light neutral backgrounds, green outgoing bubbles.
val TelegramBlue = Color(0xFF2AABEE)
val TelegramBlueDark = Color(0xFF229ED9)
val BubbleOutgoing = Color(0xFFEFFDDE)
val BubbleIncoming = Color(0xFFFFFFFF)
val ChatListBackground = Color(0xFFF4F4F5)
val SystemGray = Color(0xFF8E8E93)

private val LightColors = lightColorScheme(
    primary = TelegramBlue,
    secondary = TelegramBlueDark,
    background = ChatListBackground,
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TelegramBlue,
    secondary = TelegramBlueDark,
    background = Color(0xFF0E1621),
    surface = Color(0xFF17212B),
)

@Composable
fun MeshTalkTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
