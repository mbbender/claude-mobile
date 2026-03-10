package com.claudemobile.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.claudemobile.BuildConfig
import androidx.lifecycle.viewModelScope
import com.claudemobile.model.*
import com.claudemobile.ssh.BiometricHelper
import com.claudemobile.ssh.SshConnectionService
import com.claudemobile.ssh.SshManager
import com.claudemobile.update.UpdateInfo
import com.claudemobile.update.UpdateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ssh = SshManager()
    private val prefs = app.getSharedPreferences("claude_mobile", Context.MODE_PRIVATE)
    private val appContext = app.applicationContext
    private val sessionsDir = File(app.filesDir, "sessions").also { it.mkdirs() }
    val biometric = BiometricHelper(app)
    val updater = UpdateManager(app, viewModelScope)

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
    // Sessions actively being polled by waitForResponse (to avoid duplicate reads from background poller)
    private val activelyPolling = ConcurrentHashMap.newKeySet<String>()
    val waitingSessions: StateFlow<Set<String>> = _waitingSessions.asStateFlow()

    // Track session errors
    private val _sessionErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionErrors: StateFlow<Map<String, String>> = _sessionErrors.asStateFlow()

    // Track sessions still being created on the server
    private val _pendingSessions = MutableStateFlow<Set<String>>(emptySet())
    val pendingSessions: StateFlow<Set<String>> = _pendingSessions.asStateFlow()
    private val queuedMessages = ConcurrentHashMap<String, String>()

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

    // Session summaries (generated on archive)
    private val _sessionSummaries = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionSummaries: StateFlow<Map<String, String>> = _sessionSummaries.asStateFlow()

    // Session creation timestamps
    private val _sessionTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val sessionTimestamps: StateFlow<Map<String, Long>> = _sessionTimestamps.asStateFlow()

    // Sessions list refresh state

    // Per-session connection state (connected/disconnected/reconnecting)
    private val _sessionConnectionStates = MutableStateFlow<Map<String, SessionConnectionState>>(emptyMap())
    val sessionConnectionStates: StateFlow<Map<String, SessionConnectionState>> = _sessionConnectionStates.asStateFlow()

    // Auto-connect setting
    private val _autoConnect = MutableStateFlow(prefs.getBoolean("auto_connect", false))
    val autoConnectEnabled: StateFlow<Boolean> = _autoConnect.asStateFlow()

    // Track sessions where user has sent at least one message
    private val activatedSessions = ConcurrentHashMap.newKeySet<String>()

    // Track user message count per session for auto-rename
    private val messageCount = ConcurrentHashMap<String, Int>()

    // Track one-shot sessions that auto-archive after first response
    private val oneShotSessions = ConcurrentHashMap.newKeySet<String>()

    // Display names that differ from tmux session names
    private val _displayNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val displayNames: StateFlow<Map<String, String>> = _displayNames.asStateFlow()

    // Map display name → original data directory name (for file-based protocol paths)
    private val dataDirNames = ConcurrentHashMap<String, String>()

    // Projects discovered on the remote server
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    // Track which project a session belongs to (sessionId → projectDir)
    private val sessionProjectDirs = ConcurrentHashMap<String, String>()
    private val _sessionProjects = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionProjects: StateFlow<Map<String, String>> = _sessionProjects.asStateFlow()

    // Currently selected project filter in the header
    private val _selectedProject = MutableStateFlow<Project?>(null)
    val selectedProject: StateFlow<Project?> = _selectedProject.asStateFlow()

    fun selectProject(project: Project?) {
        _selectedProject.value = project
    }

    // Pending image attachment
    private val _pendingImageUri = MutableStateFlow<Uri?>(null)
    val pendingImageUri: StateFlow<Uri?> = _pendingImageUri.asStateFlow()

    private var pollJob: Job? = null
    private var reconnectJob: Job? = null

    private val funnyAdjectives = listOf(
        "Cosmic", "Sneaky", "Fuzzy", "Turbo", "Spicy", "Groovy", "Wobbly", "Crispy",
        "Mystic", "Chunky", "Zippy", "Bouncy", "Salty", "Dizzy", "Funky", "Silly",
        "Rowdy", "Cheeky", "Toasty", "Zesty", "Snappy", "Quirky", "Peppy", "Jiffy",
        "Breezy", "Nifty", "Giddy", "Plucky", "Swanky", "Witty", "Dapper", "Jazzy"
    )
    private val funnyNouns = listOf(
        "Penguin", "Taco", "Rocket", "Waffle", "Wizard", "Llama", "Narwhal", "Pickle",
        "Kraken", "Muffin", "Yeti", "Burrito", "Otter", "Panda", "Cactus", "Platypus",
        "Phoenix", "Quokka", "Noodle", "Walrus", "Badger", "Falcon", "Donut", "Gecko",
        "Moose", "Squid", "Pretzel", "Hamster", "Parrot", "Truffle", "Alpaca", "Bison"
    )

    init {
        loadActiveSessions()
        loadArchivedSessions()
    }

    val appVersionName: String = BuildConfig.BUILD_LABEL

    private fun startConnectionService(status: String = "Connected") {
        try {
            val intent = Intent(appContext, SshConnectionService::class.java)
            appContext.startForegroundService(intent)
            updateConnectionServiceStatus(status)
        } catch (_: Exception) {}
    }

    private fun stopConnectionService() {
        try {
            val intent = Intent(appContext, SshConnectionService::class.java).apply {
                action = SshConnectionService.ACTION_STOP
            }
            appContext.startService(intent)
        } catch (_: Exception) {}
    }

    private fun updateConnectionServiceStatus(status: String) {
        try {
            val intent = Intent(appContext, SshConnectionService::class.java).apply {
                action = SshConnectionService.ACTION_UPDATE_STATUS
                putExtra(SshConnectionService.EXTRA_STATUS, status)
            }
            appContext.startService(intent)
        } catch (_: Exception) {}
    }

    val savedConfig: SshConfig
        get() = SshConfig(
            host = prefs.getString("host", "") ?: "",
            port = prefs.getInt("port", 22),
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            keyPath = prefs.getString("keyPath", "") ?: ""
        )

    fun setAutoConnect(enabled: Boolean) {
        _autoConnect.value = enabled
        prefs.edit().putBoolean("auto_connect", enabled).apply()
    }

    /** Try auto-connect using saved credentials — no UI interaction needed. */
    fun tryAutoConnect() {
        if (!_autoConnect.value) return
        val config = if (biometric.hasStoredCredentials) {
            biometric.loadCredentials()
        } else {
            val saved = savedConfig
            if (saved.host.isBlank()) return
            saved
        }
        connectInBackground(config)
    }

    /** Connect in background — goes straight to sessions list, connects SSH behind the scenes. */
    private fun connectInBackground(config: SshConfig) {
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                ssh.connect(config)
                _connectionState.value = ConnectionState.CONNECTED
                val hostname = ssh.getHostname()
                _connectionLabel.value = "${config.username}@$hostname"
                startConnectionService("${config.username}@$hostname")

                // Merge live tmux sessions with local cache
                refreshAndMergeSessions(config)

                startPolling()
                reconnectSessionsSequentially()
                checkForUpdate()
                fetchProjects()
            } catch (e: Exception) {
                // Auto-connect failed — fall back to connect screen silently
                _connectionState.value = ConnectionState.DISCONNECTED
                _autoConnect.value = false
                stopConnectionService()
            }
        }
    }

    fun connect(config: SshConfig) {
        pollJob?.cancel()
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
                startConnectionService("${config.username}@$hostname")

                // Enable auto-connect after first successful manual connect
                setAutoConnect(true)

                refreshAndMergeSessions(config)
                startPolling()
                reconnectSessionsSequentially()
                checkForUpdate()
                fetchProjects()
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR
                val authMethod = if (config.keyPath.isNotBlank()) "key:${config.keyPath}" else "password"
                _errorMessage.value = "${e.message} [${config.username}@${config.host}:${config.port} via $authMethod]"
                stopConnectionService()
            }
        }
    }

    fun reconnect() {
        val config = savedConfig
        if (config.host.isNotBlank()) {
            connect(config)
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        reconnectJob?.cancel()
        ssh.disconnect()
        stopConnectionService()
        _connectionState.value = ConnectionState.DISCONNECTED
        // Mark all sessions as disconnected but keep them in the list
        val allDisconnected = _sessionConnectionStates.value.mapValues { SessionConnectionState.DISCONNECTED }
        _sessionConnectionStates.value = allDisconnected
        _currentSession.value = null
        _connectionLabel.value = ""
    }

    /** Fetch live tmux sessions and merge with locally cached sessions. */
    private suspend fun refreshAndMergeSessions(config: SshConfig) {
        try {
            val liveSessions = ssh.listSessions()
            val archived = _archivedSessions.value
            val liveFiltered = liveSessions.filter { it.name !in archived }

            // Tag live sessions with server metadata
            val liveWithMeta = liveFiltered.map { s ->
                s.copy(serverHost = config.host, serverUsername = config.username)
            }
            val liveNames = liveWithMeta.map { it.name }.toSet()

            // Keep local-only sessions that aren't on the server (they'll show as disconnected)
            val localOnly = _sessions.value.filter { it.name !in liveNames && it.name !in archived }

            _sessions.value = liveWithMeta + localOnly

            // Mark live sessions as connected, local-only as disconnected
            val states = mutableMapOf<String, SessionConnectionState>()
            for (s in liveWithMeta) states[s.name] = SessionConnectionState.CONNECTED
            for (s in localOnly) states[s.name] = SessionConnectionState.DISCONNECTED
            _sessionConnectionStates.value = _sessionConnectionStates.value + states

            saveActiveSessions()
        } catch (_: Exception) {
            // Don't clear sessions on transient SSH errors
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            try {
                val config = savedConfig
                refreshAndMergeSessions(config)
            } catch (_: Exception) {}
        }
    }

    /** Reconnect to each tmux session sequentially to restore state. */
    private fun reconnectSessionsSequentially() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val toReconnect = _sessions.value.filter { session ->
                session.name !in _archivedSessions.value &&
                    _sessionConnectionStates.value[session.name] == SessionConnectionState.CONNECTED
            }

            for (session in toReconnect) {
                if (!isActive) break

                // Skip if already reconnected this launch (has a dataDir entry)
                if (dataDirNames.containsKey(session.name)) continue

                _sessionConnectionStates.value = _sessionConnectionStates.value +
                    (session.name to SessionConnectionState.RECONNECTING)

                try {
                    val result = ssh.reconnectSession(session.name)
                    dataDirNames[session.name] = result.dataDir

                    val existing = _chatMessages.value[session.name].orEmpty()
                    val merged = mergeReconnectMessages(existing, result.lastPrompt, result.lastResponse)
                    _chatMessages.value = _chatMessages.value + (session.name to merged)
                    if (merged.isNotEmpty()) persistMessages(session.name, merged)

                    if (result.tokens > 0) {
                        _sessionTokens.value = _sessionTokens.value + (session.name to result.tokens)
                    }
                    if (result.cost > 0) {
                        _sessionCosts.value = _sessionCosts.value + (session.name to result.cost)
                    }
                    _sessionModels.value = _sessionModels.value + (session.name to result.model)

                    _sessionConnectionStates.value = _sessionConnectionStates.value +
                        (session.name to SessionConnectionState.CONNECTED)

                    if (result.isThinking) {
                        _waitingSessions.update { it + session.name }
                        launch { waitForResponse(session.name, result.dataDir) }
                    }
                } catch (_: Exception) {
                    _sessionConnectionStates.value = _sessionConnectionStates.value +
                        (session.name to SessionConnectionState.DISCONNECTED)
                }

                // Small delay between sessions to be gentle on SSH
                delay(300)
            }
            saveActiveSessions()
        }
    }

    fun quickCreateInteractiveSession() {
        val existing = _sessions.value.map { it.name }.toSet() +
            _archivedSessions.value +
            _chatMessages.value.keys
        var tempName: String
        do {
            tempName = "${funnyAdjectives.random()}_${funnyNouns.random()}"
        } while (tempName in existing)
        createInteractiveSession(tempName)
    }

    fun createInteractiveSession(name: String, model: com.claudemobile.model.ClaudeModel = com.claudemobile.model.ClaudeModel.OPUS) {
        // Sanitize to match what tmux will use as the session ID
        val sessionId = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")

        // Store the original pretty name as display name if it differs
        if (sessionId != name) {
            _displayNames.value = _displayNames.value + (sessionId to name)
        }

        // Use sessionId (tmux name) as the key everywhere
        _chatMessages.value = _chatMessages.value + (sessionId to emptyList())
        _sessionModels.value = _sessionModels.value + (sessionId to model)
        _sessionTimestamps.value = _sessionTimestamps.value + (sessionId to System.currentTimeMillis())
        messageCount[sessionId] = 0
        _currentSession.value = sessionId
        _pendingSessions.value = _pendingSessions.value + sessionId

        // Set up the actual tmux session in the background
        viewModelScope.launch {
            try {
                ssh.startInteractiveSession(name, model)
                dataDirNames[sessionId] = sessionId
                _sessionConnectionStates.value = _sessionConnectionStates.value +
                    (sessionId to SessionConnectionState.CONNECTED)
                refreshSessions()
            } catch (e: Exception) {
                _sessionErrors.value = _sessionErrors.value + (sessionId to "Failed to create session: ${e.message}")
            } finally {
                _pendingSessions.value = _pendingSessions.value - sessionId
                // If a message was queued while we were setting up, send it now
                val queued = queuedMessages.remove(sessionId)
                if (queued != null) {
                    sendMessage(sessionId, queued)
                }
            }
        }
    }

    private fun fetchProjects() {
        viewModelScope.launch {
            try {
                _projects.value = ssh.listProjects()
            } catch (_: Exception) {}
        }
    }

    fun createProjectSession(project: Project) {
        val existing = _sessions.value.map { it.name }.toSet() +
            _archivedSessions.value +
            _chatMessages.value.keys
        var tempName: String
        do {
            tempName = "${project.name}_${funnyAdjectives.random()}_${funnyNouns.random()}"
        } while (tempName in existing)

        val sessionId = tempName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val displayName = "${project.name} — ${tempName.substringAfter("${project.name}_").replace("_", " ")}"

        _displayNames.value = _displayNames.value + (sessionId to displayName)
        _chatMessages.value = _chatMessages.value + (sessionId to emptyList())
        _sessionModels.value = _sessionModels.value + (sessionId to ClaudeModel.OPUS)
        _sessionTimestamps.value = _sessionTimestamps.value + (sessionId to System.currentTimeMillis())
        messageCount[sessionId] = 0
        sessionProjectDirs[sessionId] = project.path
        _sessionProjects.value = _sessionProjects.value + (sessionId to project.path)
        _currentSession.value = sessionId
        _pendingSessions.value = _pendingSessions.value + sessionId

        viewModelScope.launch {
            try {
                ssh.startInteractiveSession(tempName, ClaudeModel.OPUS, project.path)
                dataDirNames[sessionId] = sessionId
                _sessionConnectionStates.value = _sessionConnectionStates.value +
                    (sessionId to SessionConnectionState.CONNECTED)
                refreshSessions()
            } catch (e: Exception) {
                _sessionErrors.value = _sessionErrors.value + (sessionId to "Failed to create session: ${e.message}")
            } finally {
                _pendingSessions.value = _pendingSessions.value - sessionId
                val queued = queuedMessages.remove(sessionId)
                if (queued != null) {
                    sendMessage(sessionId, queued)
                }
            }
        }
    }

    fun selectSession(name: String) {
        // If disconnected, can't open
        if (_sessionConnectionStates.value[name] == SessionConnectionState.DISCONNECTED) {
            _snackMessage.value = "Session is disconnected"
            return
        }

        val hasMessages = _chatMessages.value[name]?.isNotEmpty() == true
        if (hasMessages) {
            _currentSession.value = name
            // Ensure dataDir is set up for sending future messages
            if (!dataDirNames.containsKey(name)) {
                viewModelScope.launch {
                    try {
                        val result = ssh.reconnectSession(name)
                        dataDirNames[name] = result.dataDir
                        if (result.isThinking) {
                            _waitingSessions.update { it + name }
                            waitForResponse(name, result.dataDir)
                        }
                    } catch (_: Exception) {}
                }
            }
            return
        }

        // Need to reconnect — show loading screen
        _reconnecting.value = true
        viewModelScope.launch {
            try {
                val result = ssh.reconnectSession(name)
                dataDirNames[name] = result.dataDir

                val existing = _chatMessages.value[name].orEmpty()
                val merged = mergeReconnectMessages(existing, result.lastPrompt, result.lastResponse)
                _chatMessages.value = _chatMessages.value + (name to merged)

                if (result.tokens > 0) {
                    _sessionTokens.value = _sessionTokens.value + (name to result.tokens)
                }
                if (result.cost > 0) {
                    _sessionCosts.value = _sessionCosts.value + (name to result.cost)
                }
                _sessionModels.value = _sessionModels.value + (name to result.model)
                _sessionConnectionStates.value = _sessionConnectionStates.value +
                    (name to SessionConnectionState.CONNECTED)

                _currentSession.value = name

                if (result.isThinking) {
                    _waitingSessions.update { it + name }
                    waitForResponse(name, result.dataDir)
                }
            } catch (e: Exception) {
                _sessionConnectionStates.value = _sessionConnectionStates.value +
                    (name to SessionConnectionState.DISCONNECTED)
                _errorMessage.value = "Failed to reconnect: ${e.message}"
            } finally {
                _reconnecting.value = false
            }
        }
    }

    fun sendMessage(sessionName: String, message: String) {
        // If session is still being created, queue the message and show it in chat
        if (sessionName in _pendingSessions.value) {
            queuedMessages[sessionName] = message
            val newMsg = ChatMessage(content = message, isUser = true)
            var updatedMsgs: List<ChatMessage> = emptyList()
            _chatMessages.update { map ->
                val current = map[sessionName].orEmpty()
                updatedMsgs = current + newMsg
                map + (sessionName to updatedMsgs)
            }
            persistMessages(sessionName, updatedMsgs)
            _waitingSessions.update { it + sessionName }
            return
        }

        viewModelScope.launch {
            try {
                val count = messageCount[sessionName] ?: 0
                val hasImage = _pendingImageUri.value != null
                val effectiveMessage = if (message.isBlank() && hasImage) "See the attached image." else message
                val intent = if (count == 0) parseMessageIntent(effectiveMessage) else MessageIntent(effectiveMessage, null, false)
                val actualMessage = intent.cleanMessage

                // Handle model override on first message
                if (count == 0 && intent.model != null) {
                    val dataDir = dataDirNames[sessionName]
                    if (dataDir != null) {
                        ssh.killSession(sessionName, dataDir)
                        val newName = ssh.startInteractiveSession(sessionName, intent.model)
                        dataDirNames[newName] = newName
                        _sessionModels.value = _sessionModels.value + (sessionName to intent.model)
                    }
                }

                // Track one-shot sessions
                if (intent.isOneShot) {
                    oneShotSessions.add(sessionName)
                }

                // Handle image attachment
                val imageUri = _pendingImageUri.value
                var imageFileName: String? = null
                if (imageUri != null) {
                    val timestamp = System.currentTimeMillis()
                    val ext = appContext.contentResolver.getType(imageUri)?.let {
                        when {
                            it.contains("png") -> "png"
                            it.contains("webp") -> "webp"
                            else -> "jpg"
                        }
                    } ?: "jpg"
                    imageFileName = "img_${timestamp}.$ext"
                }

                val newMsg = ChatMessage(
                    content = message,
                    isUser = true,
                    imageUri = imageUri?.toString(),
                    imageName = imageFileName
                )
                var updated: List<ChatMessage> = emptyList()
                _chatMessages.update { map ->
                    updated = map[sessionName].orEmpty() + newMsg
                    map + (sessionName to updated)
                }
                persistMessages(sessionName, updated)
                activatedSessions.add(sessionName)

                // Send via file-based protocol
                var dataDir = dataDirNames[sessionName]
                if (dataDir == null) {
                    dataDir = ssh.resolveDataDir(sessionName)
                    dataDirNames[sessionName] = dataDir
                }

                // Upload image if attached
                if (imageUri != null && imageFileName != null) {
                    val dir = "/tmp/claude-mobile/$dataDir"
                    ssh.exec("mkdir -p '$dir/images'")
                    appContext.contentResolver.openInputStream(imageUri)?.use { stream ->
                        ssh.uploadFile(stream, "$dir/images/$imageFileName")
                    }
                    _pendingImageUri.value = null
                }

                ssh.sendMobileMessage(dataDir, actualMessage, imageFileName)
                _waitingSessions.update { it + sessionName }
                _sessionErrors.value = _sessionErrors.value - sessionName

                messageCount[sessionName] = count + 1

                // Auto-rename after 1st message using the user's prompt
                if (count == 0) {
                    autoRenameSession(sessionName, message)
                }

                waitForResponse(sessionName, dataDir)
            } catch (e: Exception) {
                _sessionErrors.value = _sessionErrors.value + (sessionName to (e.message ?: "Unknown error"))
                _waitingSessions.update { it - sessionName }
            }
        }
    }

    fun setPendingImage(uri: Uri?) {
        _pendingImageUri.value = uri
    }

    fun clearPendingImage() {
        _pendingImageUri.value = null
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

    private suspend fun waitForResponse(sessionName: String, dataDir: String = sessionName) {
        activelyPolling.add(sessionName)
        try {
            var waited = 0L
            var lastStatusUpdate = 0L
            val statusInterval = 90_000L
            val timeout = 600_000L
            var statusMsgIndex = 0

            while (waited < timeout) {
                delay(2000)
                waited += 2000

                val status = try {
                    ssh.getMobileStatus(dataDir)
                } catch (e: Exception) {
                    // Connection lost — keep session in waitingSessions so the background
                    // poller picks up the response after reconnection
                    return
                }

                if (status == "ready" && waited > 2500) {
                    val response = ssh.getMobileResponse(dataDir)
                    var updatedMsgs: List<ChatMessage>? = null
                    _chatMessages.update { map ->
                        val current = map[sessionName].orEmpty().filter { !it.isStatus }
                        if (response.isNotBlank()) {
                            val assistantMsg = ChatMessage(content = response, isUser = false)
                            updatedMsgs = current + assistantMsg
                            map + (sessionName to updatedMsgs!!)
                        } else {
                            map + (sessionName to current)
                        }
                    }
                    updatedMsgs?.let { persistMessages(sessionName, it) }
                    val (tokens, cost) = ssh.getMobileTokens(dataDir)
                    if (tokens > 0) _sessionTokens.value = _sessionTokens.value + (sessionName to tokens)
                    if (cost > 0) _sessionCosts.value = _sessionCosts.value + (sessionName to cost)
                    _waitingSessions.update { it - sessionName }

                    saveActiveSessions()

                    if (sessionName in oneShotSessions) {
                        oneShotSessions.remove(sessionName)
                        delay(2000)
                        archiveSession(sessionName)
                    }
                    return
                }

                if ((status == "thinking" || status == "pending") && waited - lastStatusUpdate >= statusInterval) {
                    lastStatusUpdate = waited
                    val progress = try { ssh.getMobileProgress(dataDir) } catch (_: Exception) { null }
                    val friendlyMsg = statusMessages[statusMsgIndex % statusMessages.size]
                    statusMsgIndex++
                    val statusText = if (progress != null) "$friendlyMsg\n$progress" else friendlyMsg
                    val elapsed = "${waited / 60000}m ${(waited % 60000) / 1000}s"

                    val statusMsg = ChatMessage(
                        content = "$statusText ($elapsed)",
                        isUser = false,
                        isStatus = true
                    )
                    _chatMessages.update { map ->
                        val current = map[sessionName].orEmpty().filter { !it.isStatus }
                        map + (sessionName to current + statusMsg)
                    }
                }
            }
            _sessionErrors.value = _sessionErrors.value + (sessionName to "Response timed out after 10 minutes")
            _waitingSessions.update { it - sessionName }
        } finally {
            activelyPolling.remove(sessionName)
        }
    }

    /**
     * Merge server-side last prompt/response with local chat history.
     * If local history exists and the server has a response that isn't already the last
     * assistant message, append it (it arrived while we were disconnected).
     */
    private fun mergeReconnectMessages(
        existing: List<ChatMessage>,
        lastPrompt: String?,
        lastResponse: String?
    ): List<ChatMessage> {
        if (existing.isEmpty()) {
            // No local history — use whatever the server has
            val msgs = mutableListOf<ChatMessage>()
            if (lastPrompt != null) msgs.add(ChatMessage(content = lastPrompt, isUser = true))
            if (lastResponse != null) msgs.add(ChatMessage(content = lastResponse, isUser = false))
            return msgs
        }

        // Local history exists — check if server has a new response we missed
        val filtered = existing.filter { !it.isStatus }
        if (lastResponse != null) {
            val lastAssistant = filtered.lastOrNull { !it.isUser }
            if (lastAssistant == null || lastAssistant.content != lastResponse) {
                // Server has a response we don't have locally — append it
                return filtered + ChatMessage(content = lastResponse, isUser = false)
            }
        }
        return filtered
    }

    private fun autoRenameSession(sessionName: String, context: String) {
        viewModelScope.launch {
            try {
                val shortName = generateSessionName(context)
                if (shortName.isNotBlank() && shortName != sessionName) {
                    _displayNames.value = _displayNames.value + (sessionName to shortName)
                    saveActiveSessions()
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun generateSessionName(text: String): String {
        return try {
            val prompt = "In 4-6 words, write a short descriptive subject line (like an email subject) for this conversation. No quotes, no punctuation at end. Just the subject:\n\n$text"
            val raw = ssh.exec("echo ${shellEscapeForExec(prompt)} | claude -p --model claude-haiku-4-5-20251001 --max-turns 1 2>/dev/null")
            val name = raw.trim().replace(Regex("[\"'\\n]"), "").take(40).trim()
            if (name.isNotBlank() && name.length > 3) name else fallbackSessionName(text)
        } catch (_: Exception) {
            fallbackSessionName(text)
        }
    }

    private fun fallbackSessionName(text: String): String {
        val words = text.replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .filter { it.lowercase() !in setOf("the", "and", "for", "that", "this", "with", "from", "have", "can", "please", "could", "would", "should") }
            .take(4)
            .map { it.replaceFirstChar { c -> c.uppercase() } }
        return if (words.isNotEmpty()) words.joinToString(" ").take(40) else ""
    }

    private fun shellEscapeForExec(s: String): String {
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private data class MessageIntent(
        val cleanMessage: String,
        val model: com.claudemobile.model.ClaudeModel?,
        val isOneShot: Boolean
    )

    private fun parseMessageIntent(message: String): MessageIntent {
        var clean = message
        var model: com.claudemobile.model.ClaudeModel? = null
        var isOneShot = false

        val sonnetPattern = Regex("\\b(use|with|using|in)\\s+sonnet\\b", RegexOption.IGNORE_CASE)
        if (sonnetPattern.containsMatchIn(clean)) {
            model = com.claudemobile.model.ClaudeModel.SONNET
            clean = sonnetPattern.replace(clean, "").trim()
        }

        val oneShotPattern = Regex("\\b(one[- ]?shot|as a task|run (this )?as (a )?task)\\b", RegexOption.IGNORE_CASE)
        if (oneShotPattern.containsMatchIn(clean)) {
            isOneShot = true
            clean = oneShotPattern.replace(clean, "").trim()
        }

        clean = clean.replace(Regex("\\s{2,}"), " ").trim()
        clean = clean.replace(Regex("^[,.:;]+\\s*"), "").replace(Regex("\\s*[,.:;]+$"), "").trim()

        return MessageIntent(clean, model, isOneShot)
    }

    fun killSession(sessionName: String) {
        val dataDir = dataDirNames[sessionName] ?: sessionName
        _sessions.value = _sessions.value.filter { it.name != sessionName }
        _chatMessages.value = _chatMessages.value - sessionName
        deletePersistedMessages(sessionName)
        _sessionConnectionStates.value = _sessionConnectionStates.value - sessionName
        activatedSessions.remove(sessionName)
        messageCount.remove(sessionName)
        dataDirNames.remove(sessionName)
        _sessionTokens.value = _sessionTokens.value - sessionName
        _sessionCosts.value = _sessionCosts.value - sessionName
        _sessionModels.value = _sessionModels.value - sessionName
        _displayNames.value = _displayNames.value - sessionName
        if (_currentSession.value == sessionName) _currentSession.value = null
        saveActiveSessions()

        viewModelScope.launch {
            try { ssh.killSession(sessionName, dataDir) } catch (_: Exception) {}
        }
    }

    fun archiveSession(sessionName: String) {
        val dataDir = dataDirNames[sessionName] ?: sessionName
        _archivedSessions.value = _archivedSessions.value + sessionName

        _sessions.value = _sessions.value.filter { it.name != sessionName }
        _sessionConnectionStates.value = _sessionConnectionStates.value - sessionName
        activatedSessions.remove(sessionName)
        dataDirNames.remove(sessionName)
        if (_currentSession.value == sessionName) {
            _currentSession.value = null
        }
        saveArchivedSessions()
        saveActiveSessions()

        viewModelScope.launch {
            // Generate summary from chat messages
            try {
                val msgs = _chatMessages.value[sessionName].orEmpty()
                    .filter { !it.isStatus }
                if (msgs.isNotEmpty()) {
                    val transcript = msgs.joinToString("\n") { m ->
                        val role = if (m.isUser) "User" else "Claude"
                        "$role: ${m.content.take(200)}"
                    }.take(1500)
                    val prompt = "Summarize this session in 1-2 short sentences. What did the user ask for and what was done? Be specific about actions taken (files edited, bugs fixed, features added). No quotes:\n\n$transcript"
                    val summary = ssh.exec("echo ${shellEscapeForExec(prompt)} | claude -p --model claude-haiku-4-5-20251001 --max-turns 1 2>/dev/null").trim()
                    if (summary.isNotBlank() && summary.length > 5) {
                        _sessionSummaries.value = _sessionSummaries.value + (sessionName to summary.take(200))
                        saveArchivedSessions()
                    }
                }
            } catch (_: Exception) {}

            try {
                val dir = dataDir
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

            delay(10_000)
            try {
                val liveSessions = ssh.listSessions()
                val stillAlive = liveSessions.any { it.name == sessionName }
                if (stillAlive) {
                    _archivedSessions.value = _archivedSessions.value - sessionName
                    _sessions.value = _sessions.value + liveSessions.filter { it.name == sessionName }
                    _sessionConnectionStates.value = _sessionConnectionStates.value +
                        (sessionName to SessionConnectionState.CONNECTED)
                    saveArchivedSessions()
                    saveActiveSessions()
                }
            } catch (_: Exception) {}
        }
    }

    fun viewArchivedSession(sessionName: String) {
        _currentSession.value = sessionName
    }

    fun dismissArchivedSession(sessionName: String) {
        _archivedSessions.value = _archivedSessions.value - sessionName
        _chatMessages.value = _chatMessages.value - sessionName
        deletePersistedMessages(sessionName)
        _sessionTokens.value = _sessionTokens.value - sessionName
        _sessionCosts.value = _sessionCosts.value - sessionName
        _sessionModels.value = _sessionModels.value - sessionName
        _sessionSummaries.value = _sessionSummaries.value - sessionName
        _sessionTimestamps.value = _sessionTimestamps.value - sessionName
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

    fun renameSessionManual(tmuxName: String, newDisplayName: String) {
        _displayNames.value = _displayNames.value + (tmuxName to newDisplayName)
        saveActiveSessions()
    }

    fun clearCurrentSession() {
        _currentSession.value = null
        refreshSessions()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun onAppForeground() {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            // Check if SSH is still actually alive
            viewModelScope.launch {
                if (!ssh.isConnected) {
                    // SSH dropped while backgrounded — reconnect everything
                    try {
                        ssh.reconnect()
                        _connectionState.value = ConnectionState.CONNECTED
                    } catch (_: Exception) {
                        _connectionState.value = ConnectionState.ERROR
                        _errorMessage.value = "Connection lost. Tap to reconnect."
                        val failStates = _sessionConnectionStates.value.mapValues { SessionConnectionState.DISCONNECTED }
                        _sessionConnectionStates.value = failStates
                        return@launch
                    }
                }
                val config = savedConfig
                refreshAndMergeSessions(config)
                startPolling()
                reconnectSessionsSequentially()
            }
        }
    }

    fun onAppBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollSessionOutput(sessionName: String) {
        // No-op for background polling — responses are captured in waitForResponse
    }

    private var consecutivePollFailures = 0

    private fun startPolling() {
        pollJob?.cancel()
        consecutivePollFailures = 0
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                try {
                    val result = ssh.listSessions()
                    val archived = _archivedSessions.value
                    val live = result.filter { it.name !in archived }
                    val liveNames = live.map { it.name }.toSet()

                    val localOnly = _sessions.value.filter { it.name !in liveNames && it.name !in archived }
                    _sessions.value = live + localOnly

                    val states = _sessionConnectionStates.value.toMutableMap()
                    for (s in live) states[s.name] = SessionConnectionState.CONNECTED
                    for (s in localOnly) {
                        if (states[s.name] != SessionConnectionState.RECONNECTING) {
                            states[s.name] = SessionConnectionState.DISCONNECTED
                        }
                    }
                    _sessionConnectionStates.value = states

                    // Check if any waiting sessions have finished (skip ones actively polled by waitForResponse)
                    val waiting = _waitingSessions.value - activelyPolling
                    if (waiting.isNotEmpty()) {
                        for (name in waiting) {
                            val dataDir = dataDirNames[name] ?: name
                            try {
                                val status = ssh.getMobileStatus(dataDir)
                                if (status == "ready") {
                                    val response = ssh.getMobileResponse(dataDir)
                                    var updatedMsgs: List<ChatMessage>? = null
                                    _chatMessages.update { map ->
                                        val current = map[name].orEmpty().filter { !it.isStatus }
                                        if (response.isNotBlank()) {
                                            val assistantMsg = ChatMessage(content = response, isUser = false)
                                            updatedMsgs = current + assistantMsg
                                            map + (name to updatedMsgs!!)
                                        } else {
                                            map + (name to current)
                                        }
                                    }
                                    updatedMsgs?.let { persistMessages(name, it) }
                                    val (tokens, cost) = ssh.getMobileTokens(dataDir)
                                    if (tokens > 0) _sessionTokens.value = _sessionTokens.value + (name to tokens)
                                    if (cost > 0) _sessionCosts.value = _sessionCosts.value + (name to cost)
                                    _waitingSessions.update { it - name }
                                    saveActiveSessions()
                                }
                            } catch (_: Exception) { /* ignore per-session errors */ }
                        }
                    }

                    _currentSession.value?.let { pollSessionOutput(it) }
                    if (consecutivePollFailures > 0) {
                        consecutivePollFailures = 0
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                } catch (_: Exception) {
                    consecutivePollFailures++
                    if (consecutivePollFailures >= 3) {
                        _connectionState.value = ConnectionState.CONNECTING
                        // Set all active sessions to RECONNECTING so they pulse
                        val reconnStates = _sessionConnectionStates.value.toMutableMap()
                        val archived = _archivedSessions.value
                        for (session in _sessions.value) {
                            if (session.name !in archived &&
                                reconnStates[session.name] == SessionConnectionState.CONNECTED) {
                                reconnStates[session.name] = SessionConnectionState.RECONNECTING
                            }
                        }
                        _sessionConnectionStates.value = reconnStates
                        val reconnected = ssh.reconnect()
                        if (reconnected) {
                            consecutivePollFailures = 0
                            _connectionState.value = ConnectionState.CONNECTED
                            refreshSessions()
                        } else if (consecutivePollFailures >= 6) {
                            _connectionState.value = ConnectionState.ERROR
                            _errorMessage.value = "Connection lost. Tap to reconnect."
                            // Set reconnecting sessions to disconnected
                            val failStates = _sessionConnectionStates.value.toMutableMap()
                            for ((name, state) in failStates) {
                                if (state == SessionConnectionState.RECONNECTING) {
                                    failStates[name] = SessionConnectionState.DISCONNECTED
                                }
                            }
                            _sessionConnectionStates.value = failStates
                            pollJob?.cancel()
                        }
                    }
                }
            }
        }
    }

    // --- Active session persistence ---

    private fun saveActiveSessions() {
        val active = _sessions.value
        if (active.isEmpty()) {
            prefs.edit().remove("active_sessions").apply()
            return
        }
        try {
            val root = JSONObject()
            val names = JSONArray()
            for (session in active) {
                names.put(session.name)
                root.put("host_${session.name}", session.serverHost)
                root.put("user_${session.name}", session.serverUsername)

                _chatMessages.value[session.name]?.let { chatMsgs ->
                    val arr = JSONArray()
                    for (msg in chatMsgs) {
                        if (msg.isStatus) continue
                        val m = JSONObject()
                        m.put("content", msg.content)
                        m.put("isUser", msg.isUser)
                        m.put("timestamp", msg.timestamp)
                        arr.put(m)
                    }
                    root.put("msgs_${session.name}", arr)
                }
                _sessionTokens.value[session.name]?.let { root.put("tokens_${session.name}", it) }
                _sessionCosts.value[session.name]?.let { root.put("cost_${session.name}", it) }
                _sessionModels.value[session.name]?.let { root.put("model_${session.name}", it.id) }
                dataDirNames[session.name]?.let { root.put("datadir_${session.name}", it) }
                _displayNames.value[session.name]?.let { root.put("displayname_${session.name}", it) }
                _sessionTimestamps.value[session.name]?.let { root.put("timestamp_${session.name}", it) }
            }
            root.put("names", names)
            prefs.edit().putString("active_sessions", root.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadActiveSessions() {
        val json = prefs.getString("active_sessions", null) ?: return
        try {
            val root = JSONObject(json)
            val names = root.optJSONArray("names") ?: return
            val sessions = mutableListOf<ClaudeSession>()
            val msgs = mutableMapOf<String, List<ChatMessage>>()
            val tokens = mutableMapOf<String, Long>()
            val costs = mutableMapOf<String, Double>()
            val models = mutableMapOf<String, com.claudemobile.model.ClaudeModel>()
            val connStates = mutableMapOf<String, SessionConnectionState>()
            val loadedDisplayNames = mutableMapOf<String, String>()
            val timestamps = mutableMapOf<String, Long>()

            for (i in 0 until names.length()) {
                val name = names.getString(i)
                val host = root.optString("host_$name", "")
                val user = root.optString("user_$name", "")

                sessions.add(ClaudeSession(
                    name = name,
                    isRunning = false,
                    serverHost = host,
                    serverUsername = user
                ))

                // All locally loaded sessions start as disconnected
                connStates[name] = SessionConnectionState.DISCONNECTED

                root.optJSONArray("msgs_$name")?.let { arr ->
                    val chatMsgs = mutableListOf<ChatMessage>()
                    for (j in 0 until arr.length()) {
                        val m = arr.getJSONObject(j)
                        chatMsgs.add(ChatMessage(
                            content = m.getString("content"),
                            isUser = m.getBoolean("isUser"),
                            timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                            imageUri = if (m.has("imageUri")) m.getString("imageUri") else null,
                            imageName = if (m.has("imageName")) m.getString("imageName") else null
                        ))
                    }
                    msgs[name] = chatMsgs
                }
                if (root.has("tokens_$name")) tokens[name] = root.getLong("tokens_$name")
                if (root.has("cost_$name")) costs[name] = root.getDouble("cost_$name")
                if (root.has("model_$name")) {
                    models[name] = com.claudemobile.model.ClaudeModel.fromId(root.getString("model_$name"))
                }
                if (root.has("datadir_$name")) {
                    dataDirNames[name] = root.getString("datadir_$name")
                }
                if (root.has("displayname_$name")) {
                    loadedDisplayNames[name] = root.getString("displayname_$name")
                }
                if (root.has("timestamp_$name")) {
                    timestamps[name] = root.getLong("timestamp_$name")
                }
            }

            _sessions.value = sessions
            _chatMessages.value = _chatMessages.value + msgs
            _sessionTokens.value = _sessionTokens.value + tokens
            _sessionCosts.value = _sessionCosts.value + costs
            _sessionModels.value = _sessionModels.value + models
            _sessionConnectionStates.value = _sessionConnectionStates.value + connStates
            _displayNames.value = _displayNames.value + loadedDisplayNames
            _sessionTimestamps.value = _sessionTimestamps.value + timestamps
        } catch (_: Exception) {}
    }

    // --- Archived session persistence ---

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
            val summaries = mutableMapOf<String, String>()
            val loadedDisplayNames = mutableMapOf<String, String>()
            val timestamps = mutableMapOf<String, Long>()

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
                            timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                            imageUri = if (m.has("imageUri")) m.getString("imageUri") else null,
                            imageName = if (m.has("imageName")) m.getString("imageName") else null
                        ))
                    }
                    msgs[name] = chatMsgs
                }
                if (root.has("tokens_$name")) tokens[name] = root.getLong("tokens_$name")
                if (root.has("cost_$name")) costs[name] = root.getDouble("cost_$name")
                if (root.has("model_$name")) {
                    models[name] = com.claudemobile.model.ClaudeModel.fromId(root.getString("model_$name"))
                }
                if (root.has("summary_$name")) {
                    summaries[name] = root.getString("summary_$name")
                }
                if (root.has("displayname_$name")) {
                    loadedDisplayNames[name] = root.getString("displayname_$name")
                }
                if (root.has("timestamp_$name")) {
                    timestamps[name] = root.getLong("timestamp_$name")
                }
            }

            _archivedSessions.value = archived
            _chatMessages.value = _chatMessages.value + msgs
            _sessionTokens.value = _sessionTokens.value + tokens
            _sessionCosts.value = _sessionCosts.value + costs
            _sessionModels.value = _sessionModels.value + models
            _sessionSummaries.value = _sessionSummaries.value + summaries
            _displayNames.value = _displayNames.value + loadedDisplayNames
            _sessionTimestamps.value = _sessionTimestamps.value + timestamps
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
                _sessionSummaries.value[name]?.let { root.put("summary_$name", it) }
                _displayNames.value[name]?.let { root.put("displayname_$name", it) }
                _sessionTimestamps.value[name]?.let { root.put("timestamp_$name", it) }
            }
            root.put("names", names)
            prefs.edit().putString("archived_sessions", root.toString()).apply()
        } catch (_: Exception) {}
    }

    // --- File-based message persistence ---

    private fun sessionFileName(sessionName: String): String {
        val safe = sessionName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(60)
        val hash = sessionName.hashCode().toUInt().toString(16)
        return "${safe}_${hash}.json"
    }

    private fun persistMessages(sessionName: String, messages: List<ChatMessage>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val realMessages = messages.filter { !it.isStatus }
                if (realMessages.isEmpty()) return@launch
                val arr = JSONArray()
                for (msg in realMessages) {
                    val obj = JSONObject()
                    obj.put("content", msg.content)
                    obj.put("isUser", msg.isUser)
                    obj.put("timestamp", msg.timestamp)
                    if (msg.imageUri != null) obj.put("imageUri", msg.imageUri)
                    if (msg.imageName != null) obj.put("imageName", msg.imageName)
                    arr.put(obj)
                }
                val wrapper = JSONObject()
                wrapper.put("session", sessionName)
                wrapper.put("messages", arr)
                wrapper.put("updatedAt", System.currentTimeMillis())
                File(sessionsDir, sessionFileName(sessionName)).writeText(wrapper.toString())
            } catch (_: Exception) {}
        }
    }

    private fun deletePersistedMessages(sessionName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { File(sessionsDir, sessionFileName(sessionName)).delete() } catch (_: Exception) {}
        }
    }

    private fun renamePersistedMessages(oldName: String, newName: String) {
        val msgs = _chatMessages.value[newName].orEmpty()
        deletePersistedMessages(oldName)
        if (msgs.isNotEmpty()) persistMessages(newName, msgs)
    }

    override fun onCleared() {
        super.onCleared()
        saveActiveSessions()
        stopConnectionService()
        ssh.disconnect()
    }
}
