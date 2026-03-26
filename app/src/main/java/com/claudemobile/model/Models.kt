package com.claudemobile.model

data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val keyPath: String = ""
)

enum class SessionConnectionState {
    DISCONNECTED,
    RECONNECTING,
    CONNECTED
}

data class ClaudeSession(
    val name: String,
    val isRunning: Boolean,
    val windowId: String = "",
    val lastOutput: String = "",
    val serverHost: String = "",
    val serverUsername: String = ""
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
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null,
    val imageName: String? = null
)

data class Project(
    val name: String,
    val path: String
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}
