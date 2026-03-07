package com.claudemobile.model

data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val keyPath: String = ""
)

data class ClaudeSession(
    val name: String,
    val isRunning: Boolean,
    val windowId: String = "",
    val lastOutput: String = ""
)

enum class ClaudeModel(val id: String, val displayName: String) {
    OPUS("claude-opus-4-6", "Opus"),
    SONNET("claude-sonnet-4-6", "Sonnet");

    companion object {
        fun fromId(id: String): ClaudeModel =
            entries.find { it.id == id } ?: OPUS
    }
}

data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val isStatus: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
