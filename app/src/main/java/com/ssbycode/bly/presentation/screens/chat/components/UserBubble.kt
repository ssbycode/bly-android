package com.ssbycode.bly.presentation.screens.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ssbycode.bly.utils.EmojiManager

@Composable
fun UserBubble(userId: String, emojiManager: EmojiManager = viewModel()) {
    val emoji = remember { emojiManager.getOrCreateEmoji(userId) }

    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color.Gray.copy(alpha = 0.1f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}