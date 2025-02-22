package com.ssbycode.bly.utils

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EmojiManager : ViewModel() {

    private val _userEmojis = MutableStateFlow<Map<String, String>>(emptyMap())
    val userEmojis = _userEmojis.asStateFlow()

    private val animalEmojis = listOf(
        // Mamíferos (40)
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯",
        "🦁", "🐮", "🐷", "🐵", "🦒", "🦝", "🐺", "🐴", "🦓", "🦬",
        "🦘", "🦡", "🦥", "🦦", "🦨", "🦫", "🐿️", "🦍", "🦣", "🦏",
        "🦛", "🐪", "🐫", "🦙", "🦌", "🐃", "🐂", "🐄", "🐎", "🦧",

        // Gatos (6)
        "🐱", "😺", "😸", "😹", "😻", "😽",

        // Aves (15)
        "🦜", "🦆", "🦅", "🦉", "🦤", "🦢", "🦩", "🦚", "🦃", "🐧",
        "🐦", "🐤", "🦜",

        // Marinhos (15)
        "🐙", "🦑", "🦐", "🦞", "🦀", "🐡", "🐠", "🐟", "🐳", "🐋",
        "🐬", "🦈", "🦭", "🐚", "🪼",

        // Répteis, Anfíbios e Dinossauros (12)
        "🐊", "🐢", "🦎", "🐍", "🐸", "🐉", "🦕", "🦖",
        "🦕", "🐲",

        // Insetos e Aracnídeos (12)
        "🦋", "🐛", "🐜", "🐝", "🪲", "🐞", "🦗", "🕷️", "🦂", "🪳",
        "🪰", "🪱",

        // Outros fantásticos (4)
        "🐲", "🐉", "🦕", "🦖"
    )

    private val expressionEmojis = listOf(
        "😊", "😄", "😃", "🥰", "🤓", "😎", "🤠", "😇", "🤗", "🤪",
        "🧐", "🤨", "😌", "😛", "😋", "🤩", "🥳", "😺", "😸", "😹"
    )

    private val usedEmojis = mutableSetOf<String>()
    private val usedExpressionEmojis = mutableSetOf<String>()

    fun getOrCreateEmoji(userId: String): String {
        val currentEmojis = _userEmojis.value

        // Retorna o emoji existente se já foi atribuído ao usuário
        if (currentEmojis.containsKey(userId)) {
            return currentEmojis[userId]!!
        }

        // Pega um emoji disponível da lista de animais
        val availableAnimals = animalEmojis.filter { !usedEmojis.contains(it) }
        val newEmoji = availableAnimals.randomOrNull()

        return if (newEmoji != null) {
            assignEmoji(userId, newEmoji, usedEmojis)
            newEmoji
        } else {
            // Fallback para emojis de expressões faciais
            val availableExpressions = expressionEmojis.filter { !usedExpressionEmojis.contains(it) }
            val fallbackEmoji = availableExpressions.randomOrNull() ?: "👤"

            assignEmoji(userId, fallbackEmoji, usedExpressionEmojis)
            fallbackEmoji
        }
    }

    private fun assignEmoji(userId: String, emoji: String, usedSet: MutableSet<String>) {
        viewModelScope.launch(Dispatchers.Main) {
            _userEmojis.value = _userEmojis.value + (userId to emoji)
            usedSet.add(emoji)
        }
    }

    fun resetEmojis() {
        viewModelScope.launch(Dispatchers.Main) {
            _userEmojis.value = emptyMap()
            usedEmojis.clear()
            usedExpressionEmojis.clear()
        }
    }
}