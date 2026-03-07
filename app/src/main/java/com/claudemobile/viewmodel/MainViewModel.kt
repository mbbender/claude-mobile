package com.claudemobile.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claudemobile.model.*
import com.claudemobile.ssh.BiometricHelper
import com.claudemobile.ssh.SshManager
import com.claudemobile.update.UpdateInfo
import com.claudemobile.update.UpdateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ssh = SshManager()
    private val prefs = app.getSharedPreferences("claude_mobile", Context.MODE_PRIVATE)
    val biometric = BiometricHelper(app)
    val updater = UpdateManager(app)

    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _sessions = MutableStateFlow<List<ClaudeSession>>(emptyList())
    val sessions: StateFlow<List<ClaudeSession>> = _sessions.asStateFlow()

    private val _chatMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chatMessages: StateFlow<Map<String, List<ChatMessage>>> = _chatMessages.asStateFlow()

    private val _currentSession = MutableStateFlow<String?>(null)
    val currentSession: StateFlow<String?> = _currentSession.asStateFlow()

    private val _connectionLabel = MutableStateFlow("")
    val connectionLabel: StateFlow<String> = _connectionLabel.asStateFlow()

    private val _creatingSession = MutableStateFlow(false)
    val creatingSession: StateFlow<Boolean> = _creatingSession.asStateFlow()

    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    private val _snackMessage = MutableStateFlow<String?>(null)
    val snackMessage: StateFlow<String?> = _snackMessage.asStateFlow()

    // Track which sessions are waiting for a response
    private val _waitingSessions = MutableStateFlow<Set<String>>(emptySet())
    val waitingSessions: StateFlow<Set<String>> = _waitingSessions.asStateFlow()

    // Track session errors
    private val _sessionErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionErrors: StateFlow<Map<String, String>> = _sessionErrors.asStateFlow()

    // Track token usage per session (display name → total tokens)
    private val _sessionTokens = MutableStateFlow<Map<String, Long>>(emptyMap())
    val sessionTokens: StateFlow<Map<String, Long>> = _sessionTokens.asStateFlow()

    // Track cost per session
    private val _sessionCosts = MutableStateFlow<Map<String, Double>>(emptyMap())
    val sessionCosts: StateFlow<Map<String, Double>> = _sessionCosts.asStateFlow()

    // Track model per session
    private val _sessionModels = MutableStateFlow<Map<String, com.claudemobile.model.ClaudeModel>>(emptyMap())
    val sessionModels: StateFlow<Map<String, com.claudemobile.model.ClaudeModel>> = _sessionModels.asStateFlow()

    // Archived sessions (tmux killed, but chat history preserved)
    private val _archivedSessions = MutableStateFlow<Set<String>>(emptySet())
    val archivedSessions: StateFlow<Set<String>> = _archivedSessions.asStateFlow()

    // Track sessions where user has sent at least one message
    private val activatedSessions = mutableSetOf<String>()

    // Track user message count per session for auto-rename
    private val messageCount = mutableMapOf<String, Int>()

    // Display names that differ from tmux session names
    private val _displayNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val displayNames: StateFlow<Map<String, String>> = _displayNames.asStateFlow()

    // Map display name → original data directory name (for file-based protocol paths)
    private val dataDirNames = mutableMapOf<String, String>()

    private var pollJob: Job? = null
    private val sessionCounter = AtomicInteger(0)

    init {
        loadArchivedSessions()
    }

    val appVersionName: String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    val savedConfig: SshConfig
        get() = SshConfig(
            host = prefs.getString("host", "") ?: "",
            port = prefs.getInt("port", 22),
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            keyPath = prefs.getString("keyPath", "") ?: ""
        )

    fun connect(config: SshConfig) {
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null

        prefs.edit()
            .putString("host", config.host)
            .putInt("port", config.port)
            .putString("username", config.username)
            .putString("password", config.password)
            .putString("keyPath", config.keyPath)
            .apply()

        viewModelScope.launch {
            try {
                ssh.connect(config)
                _connectionState.value = ConnectionState.CONNECTED
                if (biometric.canUseBiometric) {
                    biometric.saveCredentials(config)
                }
                val hostname = ssh.getHostname()
                _connectionLabel.value = "${config.username}@$hostname"
                refreshSessions()
                startPolling()
                checkForUpdate()
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = e.message ?: "Connection failed"
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        ssh.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _sessions.value = emptyList()
        _currentSession.value = null
        _connectionLabel.value = ""
    }

    fun refreshSessions() {
        viewModelScope.launch {
            try {
                val result = ssh.listSessions()
                val archived = _archivedSessions.value
                _sessions.value = result.filter { it.name !in archived }
            } catch (_: Exception) {
                // Don't clear sessions on transient SSH errors
            }
        }
    }

    fun quickCreateInteractiveSession(model: com.claudemobile.model.ClaudeModel = com.claudemobile.model.ClaudeModel.OPUS) {
        val num = sessionCounter.incrementAndGet()
        val tempName = "session-$num"
        createInteractiveSession(tempName, model)
    }

    fun createInteractiveSession(name: String, model: com.claudemobile.model.ClaudeModel = com.claudemobile.model.ClaudeModel.OPUS) {
        _creatingSession.value = true
        viewModelScope.launch {
            try {
                val sessionName = ssh.startInteractiveSession(name, model)
                dataDirNames[sessionName] = sessionName  // data dir = original name
                _chatMessages.value = _chatMessages.value + (sessionName to emptyList())
                _sessionModels.value = _sessionModels.value + (sessionName to model)
                messageCount[sessionName] = 0
                refreshSessions()
                _currentSession.value = sessionName
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create session: ${e.message}"
            } finally {
                _creatingSession.value = false
            }
        }
    }

    fun createTaskSession(name: String, prompt: String, model: com.claudemobile.model.ClaudeModel = com.claudemobile.model.ClaudeModel.OPUS) {
        _creatingSession.value = true
        viewModelScope.launch {
            try {
                val sessionName = ssh.createSession(name, prompt, model)
                activatedSessions.add(sessionName)
                _sessionModels.value = _sessionModels.value + (sessionName to model)
                val msg = ChatMessage(content = prompt, isUser = true)
                _chatMessages.value = _chatMessages.value + (sessionName to listOf(msg))
                messageCount[sessionName] = 1
                refreshSessions()
                _currentSession.value = sessionName
            } catch (e: Exception) {
                _errorMessage.value = "Failed to create task: ${e.message}"
            } finally {
                _creatingSession.value = false
            }
        }
    }

    fun selectSession(name: String) {
        val hasMessages = _chatMessages.value[name]?.isNotEmpty() == true
        if (hasMessages) {
            // Already have state for this session — just open it
            _currentSession.value = name
            return
        }

        // Need to reconnect — show loading screen
        _reconnecting.value = true
        viewModelScope.launch {
            try {
                val result = ssh.reconnectSession(name)
                dataDirNames[name] = result.dataDir

                val messages = mutableListOf<ChatMessage>()
                // Restore last prompt
                if (result.lastPrompt != null) {
                    messages.add(ChatMessage(content = result.lastPrompt, isUser = true))
                }
                // Restore last response (only if not currently thinking)
                if (result.lastResponse != null) {
                    messages.add(ChatMessage(content = result.lastResponse, isUser = false))
                }
                _chatMessages.value = _chatMessages.value + (name to messages)

                if (result.tokens > 0) {
                    _sessionTokens.value = _sessionTokens.value + (name to result.tokens)
                }
                if (result.cost > 0) {
                    _sessionCosts.value = _sessionCosts.value + (name to result.cost)
                }
                _sessionModels.value = _sessionModels.value + (name to result.model)

                _currentSession.value = name

                // If still thinking, show typing indicator and poll for response
                if (result.isThinking) {
                    _waitingSessions.value = _waitingSessions.value + name
                    waitForResponse(name, result.dataDir)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to reconnect: ${e.message}"
            } finally {
                _reconnecting.value = false
            }
        }
    }

    fun sendMessage(sessionName: String, message: String) {
        viewModelScope.launch {
            try {
                val current = _chatMessages.value[sessionName].orEmpty()
                val newMsg = ChatMessage(content = message, isUser = true)
                _chatMessages.value = _chatMessages.value + (sessionName to current + newMsg)
                activatedSessions.add(sessionName)

                // Send via file-based protocol (use original data dir name)
                var dataDir = dataDirNames[sessionName]
                if (dataDir == null) {
                    dataDir = ssh.resolveDataDir(sessionName)
                    dataDirNames[sessionName] = dataDir
                }
                ssh.sendMobileMessage(dataDir, message)
                _waitingSessions.value = _waitingSessions.value + sessionName
                _sessionErrors.value = _sessionErrors.value - sessionName

                val count = (messageCount[sessionName] ?: 0) + 1
                messageCount[sessionName] = count

                // Auto-rename after 1st message
                if (count == 1) {
                    autoRenameSession(sessionName, message, isFirst = true)
                }
                // Refine name after 5th message
                if (count == 5) {
                    val allUserMsgs = (_chatMessages.value[sessionName].orEmpty())
                        .filter { it.isUser }
                        .joinToString("; ") { it.content.take(80) }
                    autoRenameSession(sessionName, allUserMsgs, isFirst = false)
                }

                // Poll until Claude responds
                waitForResponse(sessionName, dataDir)
            } catch (e: Exception) {
                val currentName = dataDirNames.entries
                    .find { it.value == (dataDirNames[sessionName] ?: sessionName) }?.key ?: sessionName
                _sessionErrors.value = _sessionErrors.value + (currentName to (e.message ?: "Unknown error"))
                _waitingSessions.value = _waitingSessions.value - sessionName - currentName
            }
        }
    }

    private val statusMessages = listOf(
        "Still working on it...",
        "Claude is deep in thought...",
        "Crunching through the code...",
        "Making progress...",
        "Hang tight, still going...",
        "Almost there... maybe...",
        "This is a big one...",
        "Still at it..."
    )

    private suspend fun waitForResponse(originalSessionName: String, dataDir: String = originalSessionName) {
        var waited = 0L
        var lastStatusUpdate = 0L
        val statusInterval = 90_000L // Show status every 90 seconds
        val timeout = 600_000L // 10 minute timeout
        var statusMsgIndex = 0

        while (waited < timeout) {
            delay(2000)
            waited += 2000
            val currentName = dataDirNames.entries
                .find { it.value == dataDir }?.key ?: originalSessionName

            // Update waiting set if session was renamed
            if (currentName != originalSessionName && originalSessionName in _waitingSessions.value) {
                _waitingSessions.value = (_waitingSessions.value - originalSessionName) + currentName
            }

            val status = try {
                ssh.getMobileStatus(dataDir)
            } catch (e: Exception) {
                _sessionErrors.value = _sessionErrors.value + (currentName to "Lost connection to session")
                _waitingSessions.value = _waitingSessions.value - currentName - originalSessionName
                return
            }

            if (status == "ready" && waited > 3000) {
                // Claude finished — remove any status messages first
                val current = _chatMessages.value[currentName].orEmpty()
                    .filter { !it.isStatus }
                val response = ssh.getMobileResponse(dataDir)
                if (response.isNotBlank()) {
                    val assistantMsg = ChatMessage(content = response, isUser = false)
                    _chatMessages.value = _chatMessages.value + (currentName to current + assistantMsg)
                } else {
                    _chatMessages.value = _chatMessages.value + (currentName to current)
                }
                // Fetch token usage
                val (tokens, cost) = ssh.getMobileTokens(dataDir)
                if (tokens > 0) _sessionTokens.value = _sessionTokens.value + (currentName to tokens)
                if (cost > 0) _sessionCosts.value = _sessionCosts.value + (currentName to cost)
                _waitingSessions.value = _waitingSessions.value - currentName - originalSessionName
                return
            }

            // Periodic status update for long-running tasks
            if (status == "thinking" && waited - lastStatusUpdate >= statusInterval) {
                lastStatusUpdate = waited
                val progress = try { ssh.getMobileProgress(dataDir) } catch (_: Exception) { null }
                val friendlyMsg = statusMessages[statusMsgIndex % statusMessages.size]
                statusMsgIndex++
                val statusText = if (progress != null) "$friendlyMsg\n$progress" else friendlyMsg
                val elapsed = "${waited / 60000}m ${(waited % 60000) / 1000}s"

                val current = _chatMessages.value[currentName].orEmpty()
                    .filter { !it.isStatus }
                val statusMsg = ChatMessage(
                    content = "$statusText ($elapsed)",
                    isUser = false,
                    isStatus = true
                )
                _chatMessages.value = _chatMessages.value + (currentName to current + statusMsg)
            }
        }
        // Timed out
        val currentName = dataDirNames.entries
            .find { it.value == dataDir }?.key ?: originalSessionName
        _sessionErrors.value = _sessionErrors.value + (currentName to "Response timed out after 10 minutes")
        _waitingSessions.value = _waitingSessions.value - currentName - originalSessionName
    }

    private fun autoRenameSession(sessionName: String, context: String, isFirst: Boolean) {
        viewModelScope.launch {
            try {
                // Generate a short name from the prompt content
                val shortName = generateSessionName(context)
                if (shortName.isNotBlank() && shortName != sessionName) {
                    val newName = ssh.renameSession(sessionName, shortName)

                    // Update all references from old name to new name
                    val msgs = _chatMessages.value[sessionName].orEmpty()
                    _chatMessages.value = (_chatMessages.value - sessionName) + (newName to msgs)

                    if (activatedSessions.remove(sessionName)) {
                        activatedSessions.add(newName)
                    }
                    messageCount[newName] = messageCount.remove(sessionName) ?: 0
                    // Transfer data dir mapping: new display name → same original data dir
                    val originalDir = dataDirNames.remove(sessionName) ?: sessionName
                    dataDirNames[newName] = originalDir
                    // Transfer token/cost tracking
                    _sessionTokens.value[sessionName]?.let { t ->
                        _sessionTokens.value = (_sessionTokens.value - sessionName) + (newName to t)
                    }
                    _sessionCosts.value[sessionName]?.let { c ->
                        _sessionCosts.value = (_sessionCosts.value - sessionName) + (newName to c)
                    }
                    _sessionModels.value[sessionName]?.let { m ->
                        _sessionModels.value = (_sessionModels.value - sessionName) + (newName to m)
                    }
                    _displayNames.value = _displayNames.value - sessionName

                    if (_currentSession.value == sessionName) {
                        _currentSession.value = newName
                    }

                    refreshSessions()
                }
            } catch (_: Exception) {}
        }
    }

    private fun generateSessionName(text: String): String {
        val words = text.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .filter { it.lowercase() !in setOf("the", "and", "for", "that", "this", "with", "from", "have", "can", "please", "could", "would", "should") }
            .take(4)
            .map { it.replaceFirstChar { c -> c.uppercase() } }
        return if (words.isNotEmpty()) {
            words.joinToString(" ").take(40)
        } else {
            ""
        }
    }

    fun killSession(sessionName: String) {
        viewModelScope.launch {
            try {
                val dataDir = dataDirNames[sessionName] ?: ssh.resolveDataDir(sessionName)
                ssh.killSession(sessionName, dataDir)
                _sessions.value = _sessions.value.filter { it.name != sessionName }
                _chatMessages.value = _chatMessages.value - sessionName
                activatedSessions.remove(sessionName)
                messageCount.remove(sessionName)
                dataDirNames.remove(sessionName)
                _sessionTokens.value = _sessionTokens.value - sessionName
                _sessionCosts.value = _sessionCosts.value - sessionName
                _sessionModels.value = _sessionModels.value - sessionName
                _displayNames.value = _displayNames.value - sessionName
                if (_currentSession.value == sessionName) {
                    _currentSession.value = null
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to kill session: ${e.message}"
            }
        }
    }

    fun archiveSession(sessionName: String) {
        // Update UI immediately
        val dataDir = dataDirNames[sessionName]
        _archivedSessions.value = _archivedSessions.value + sessionName
        _sessions.value = _sessions.value.filter { it.name != sessionName }
        activatedSessions.remove(sessionName)
        dataDirNames.remove(sessionName)
        if (_currentSession.value == sessionName) {
            _currentSession.value = null
        }
        saveArchivedSessions()

        // Do SSH cleanup in background
        viewModelScope.launch {
            try {
                val dir = dataDir ?: ssh.resolveDataDir(sessionName)
                val (tokens, cost) = ssh.getMobileTokens(dir)
                if (tokens > 0) _sessionTokens.value = _sessionTokens.value + (sessionName to tokens)
                if (cost > 0) _sessionCosts.value = _sessionCosts.value + (sessionName to cost)
                if (sessionName !in _sessionModels.value) {
                    val model = ssh.getSessionModel(dir)
                    _sessionModels.value = _sessionModels.value + (sessionName to model)
                }
                saveArchivedSessions()
                ssh.killSession(sessionName, dir)
            } catch (_: Exception) {}

            // Verify after 10 seconds — if session is still alive, restore to active
            delay(10_000)
            try {
                val liveSessions = ssh.listSessions()
                val stillAlive = liveSessions.any { it.name == sessionName }
                if (stillAlive) {
                    // Kill failed — restore to active sessions
                    _archivedSessions.value = _archivedSessions.value - sessionName
                    _sessions.value = _sessions.value + liveSessions.filter { it.name == sessionName }
                    saveArchivedSessions()
                }
            } catch (_: Exception) {}
        }
    }

    fun dismissArchivedSession(sessionName: String) {
        _archivedSessions.value = _archivedSessions.value - sessionName
        _chatMessages.value = _chatMessages.value - sessionName
        _sessionTokens.value = _sessionTokens.value - sessionName
        _sessionCosts.value = _sessionCosts.value - sessionName
        _sessionModels.value = _sessionModels.value - sessionName
        messageCount.remove(sessionName)
        _displayNames.value = _displayNames.value - sessionName
        saveArchivedSessions()
    }

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private var updateDismissed = false

    fun checkForUpdate(silent: Boolean = true) {
        viewModelScope.launch {
            try {
                if (!silent) _checkingUpdate.value = true
                val update = updater.checkForUpdate()
                if (!updateDismissed) _updateAvailable.value = update
                if (!silent) {
                    _snackMessage.value = if (update != null) {
                        "Update v${update.versionName} available!"
                    } else {
                        "You're on the latest version"
                    }
                }
            } catch (e: Exception) {
                if (!silent) {
                    _snackMessage.value = "Update check failed: ${e.message}"
                }
            } finally {
                _checkingUpdate.value = false
            }
        }
    }

    fun clearSnack() {
        _snackMessage.value = null
    }

    fun installUpdate() {
        _updateAvailable.value?.let {
            _isUpdating.value = true
            _updateAvailable.value = null
            updateDismissed = true
            updater.downloadAndInstall(it) { _isUpdating.value = false }
        }
    }

    fun dismissUpdate() {
        _updateAvailable.value = null
        updateDismissed = true
    }

    fun renameSessionManual(oldName: String, newName: String) {
        viewModelScope.launch {
            try {
                val renamed = ssh.renameSession(oldName, newName)
                val msgs = _chatMessages.value[oldName].orEmpty()
                _chatMessages.value = (_chatMessages.value - oldName) + (renamed to msgs)
                if (activatedSessions.remove(oldName)) activatedSessions.add(renamed)
                messageCount[renamed] = messageCount.remove(oldName) ?: 0
                val originalDir = dataDirNames.remove(oldName) ?: oldName
                dataDirNames[renamed] = originalDir
                _sessionTokens.value[oldName]?.let { t ->
                    _sessionTokens.value = (_sessionTokens.value - oldName) + (renamed to t)
                }
                _sessionCosts.value[oldName]?.let { c ->
                    _sessionCosts.value = (_sessionCosts.value - oldName) + (renamed to c)
                }
                _sessionModels.value[oldName]?.let { m ->
                    _sessionModels.value = (_sessionModels.value - oldName) + (renamed to m)
                }
                _displayNames.value = _displayNames.value - oldName
                if (_currentSession.value == oldName) _currentSession.value = renamed
                refreshSessions()
            } catch (_: Exception) {}
        }
    }

    fun clearCurrentSession() {
        _currentSession.value = null
        refreshSessions()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private suspend fun pollSessionOutput(sessionName: String) {
        // No-op for background polling — responses are captured in waitForResponse
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                try {
                    val result = ssh.listSessions()
                    val archived = _archivedSessions.value
                    _sessions.value = result.filter { it.name !in archived }
                    _currentSession.value?.let { pollSessionOutput(it) }
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadArchivedSessions() {
        val json = prefs.getString("archived_sessions", null) ?: return
        try {
            val root = JSONObject(json)
            val names = root.optJSONArray("names") ?: return
            val archived = mutableSetOf<String>()
            val msgs = mutableMapOf<String, List<ChatMessage>>()
            val tokens = mutableMapOf<String, Long>()
            val costs = mutableMapOf<String, Double>()
            val models = mutableMapOf<String, com.claudemobile.model.ClaudeModel>()

            for (i in 0 until names.length()) {
                val name = names.getString(i)
                archived.add(name)

                root.optJSONArray("msgs_$name")?.let { arr ->
                    val chatMsgs = mutableListOf<ChatMessage>()
                    for (j in 0 until arr.length()) {
                        val m = arr.getJSONObject(j)
                        chatMsgs.add(ChatMessage(
                            content = m.getString("content"),
                            isUser = m.getBoolean("isUser"),
                            timestamp = m.optLong("timestamp", System.currentTimeMillis())
                        ))
                    }
                    msgs[name] = chatMsgs
                }
                if (root.has("tokens_$name")) tokens[name] = root.getLong("tokens_$name")
                if (root.has("cost_$name")) costs[name] = root.getDouble("cost_$name")
                if (root.has("model_$name")) {
                    models[name] = com.claudemobile.model.ClaudeModel.fromId(root.getString("model_$name"))
                }
            }

            _archivedSessions.value = archived
            _chatMessages.value = _chatMessages.value + msgs
            _sessionTokens.value = _sessionTokens.value + tokens
            _sessionCosts.value = _sessionCosts.value + costs
            _sessionModels.value = _sessionModels.value + models
        } catch (_: Exception) {}
    }

    private fun saveArchivedSessions() {
        val archived = _archivedSessions.value
        if (archived.isEmpty()) {
            prefs.edit().remove("archived_sessions").apply()
            return
        }
        try {
            val root = JSONObject()
            val names = JSONArray()
            for (name in archived) {
                names.put(name)
                _chatMessages.value[name]?.let { chatMsgs ->
                    val arr = JSONArray()
                    for (msg in chatMsgs) {
                        if (msg.isStatus) continue
                        val m = JSONObject()
                        m.put("content", msg.content)
                        m.put("isUser", msg.isUser)
                        m.put("timestamp", msg.timestamp)
                        arr.put(m)
                    }
                    root.put("msgs_$name", arr)
                }
                _sessionTokens.value[name]?.let { root.put("tokens_$name", it) }
                _sessionCosts.value[name]?.let { root.put("cost_$name", it) }
                _sessionModels.value[name]?.let { root.put("model_$name", it.id) }
            }
            root.put("names", names)
            prefs.edit().putString("archived_sessions", root.toString()).apply()
        } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        ssh.disconnect()
    }
}
