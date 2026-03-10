package com.claudemobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.claudemobile.model.ConnectionState
import com.claudemobile.ui.screens.ChatScreen
import com.claudemobile.ui.screens.ConnectScreen
import com.claudemobile.ui.screens.ReconnectLoadingScreen
import com.claudemobile.ui.screens.SessionLoadingScreen
import com.claudemobile.ui.screens.UpdateLoadingScreen
import com.claudemobile.ui.screens.SessionsScreen
import com.claudemobile.ui.theme.ClaudeMobileTheme
import com.claudemobile.viewmodel.MainViewModel

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            ClaudeMobileTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ClaudeMobileApp()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
}

@Composable
fun ClaudeMobileApp(viewModel: MainViewModel = viewModel()) {
    val connectionState by viewModel.connectionState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val updateAvailable by viewModel.updateAvailable.collectAsState()
    val connectionLabel by viewModel.connectionLabel.collectAsState()
    val creatingSession by viewModel.creatingSession.collectAsState()
    val snackMessage by viewModel.snackMessage.collectAsState()
    val checkingUpdate by viewModel.checkingUpdate.collectAsState()
    val isUpdating by viewModel.isUpdating.collectAsState()
    val downloadProgress by viewModel.updater.downloadProgress.collectAsState()
    val reconnecting by viewModel.reconnecting.collectAsState()
    val waitingSessions by viewModel.waitingSessions.collectAsState()
    val sessionErrors by viewModel.sessionErrors.collectAsState()
    val sessionTokens by viewModel.sessionTokens.collectAsState()
    val sessionCosts by viewModel.sessionCosts.collectAsState()
    val sessionModels by viewModel.sessionModels.collectAsState()
    val archivedSessions by viewModel.archivedSessions.collectAsState()
    val sessionSummaries by viewModel.sessionSummaries.collectAsState()
    val sessionConnectionStates by viewModel.sessionConnectionStates.collectAsState()
    val pendingSessions by viewModel.pendingSessions.collectAsState()
    val autoConnectEnabled by viewModel.autoConnectEnabled.collectAsState()
    val displayNames by viewModel.displayNames.collectAsState()
    val pendingImageUri by viewModel.pendingImageUri.collectAsState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.setPendingImage(it) }
    }

    val activity = LocalContext.current as FragmentActivity
    val biometric = viewModel.biometric
    val showBiometric = biometric.canUseBiometric && biometric.hasStoredCredentials
    val hasLocalSessions = sessions.isNotEmpty() || archivedSessions.isNotEmpty()

    // Auto-connect on launch if enabled
    LaunchedEffect(Unit) {
        if (autoConnectEnabled) {
            viewModel.tryAutoConnect()
        }
        viewModel.checkForUpdate()
    }

    // Pause polling when backgrounded, resume when foregrounded
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForeground()
                Lifecycle.Event.ON_PAUSE -> viewModel.onAppBackground()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Determine if we should show sessions screen even while connecting
    // (auto-connect mode: show cached sessions immediately)
    val autoConnectActive = autoConnectEnabled && hasLocalSessions &&
        connectionState != ConnectionState.ERROR

    Crossfade(
        targetState = when {
            // Show connect screen only if not auto-connecting or if error with no local data
            !autoConnectActive && connectionState != ConnectionState.CONNECTED -> "connect"
            isUpdating -> "updating"
            reconnecting -> "reconnecting"
            creatingSession -> "loading"
            currentSession != null -> "chat"
            else -> "sessions"
        },
        label = "screen"
    ) { screen ->
        when (screen) {
            "connect" -> {
                ConnectScreen(
                    connectionState = connectionState,
                    errorMessage = errorMessage,
                    savedConfig = viewModel.savedConfig,
                    onConnect = viewModel::connect,
                    onDismissError = viewModel::clearError,
                    showBiometric = showBiometric,
                    onBiometricLogin = {
                        biometric.authenticate(
                            activity = activity,
                            onSuccess = { config -> viewModel.connect(config) },
                            onError = { msg -> viewModel.clearError() }
                        )
                    },
                    updateInfo = updateAvailable,
                    onInstallUpdate = viewModel::installUpdate,
                    onDismissUpdate = viewModel::dismissUpdate,
                    autoConnectEnabled = autoConnectEnabled,
                    onToggleAutoConnect = viewModel::setAutoConnect
                )
            }
            "updating" -> {
                UpdateLoadingScreen(downloadProgress = downloadProgress)
            }
            "reconnecting" -> {
                ReconnectLoadingScreen()
            }
            "loading" -> {
                SessionLoadingScreen()
            }
            "chat" -> {
                val session = currentSession ?: return@Crossfade
                val sessionDisplayName = displayNames[session] ?: session
                ChatScreen(
                    sessionName = sessionDisplayName,
                    messages = chatMessages[session].orEmpty(),
                    connectionLabel = connectionLabel,
                    isWaiting = session in waitingSessions,
                    errorMessage = sessionErrors[session],
                    model = sessionModels[session],
                    readOnly = session in archivedSessions,
                    isPending = session in pendingSessions,
                    pendingImageUri = pendingImageUri,
                    onSendMessage = { viewModel.sendMessage(session, it) },
                    onAttachImage = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClearImage = { viewModel.clearPendingImage() },
                    onBack = { viewModel.clearCurrentSession() }
                )
            }
            "sessions" -> {
                SessionsScreen(
                    sessions = sessions,
                    onSessionClick = viewModel::selectSession,
                    onQuickNewInteractive = { viewModel.quickCreateInteractiveSession() },
                    onNewInteractive = { name -> viewModel.createInteractiveSession(name) },
                    onKillSession = viewModel::killSession,
                    onArchiveSession = viewModel::archiveSession,
                    onArchivedSessionClick = viewModel::viewArchivedSession,
                    onDismissArchived = viewModel::dismissArchivedSession,
                    onRenameSession = viewModel::renameSessionManual,
                    onRefresh = viewModel::refreshSessions,
                    onCheckUpdate = { viewModel.checkForUpdate(silent = false) },
                    updateInfo = updateAvailable,
                    onInstallUpdate = viewModel::installUpdate,
                    onDismissUpdate = viewModel::dismissUpdate,
                    checkingUpdate = checkingUpdate,
                    snackMessage = snackMessage,
                    onClearSnack = viewModel::clearSnack,
                    versionName = viewModel.appVersionName,
                    sessionTokens = sessionTokens,
                    sessionCosts = sessionCosts,
                    sessionModels = sessionModels,
                    archivedSessions = archivedSessions,
                    sessionSummaries = sessionSummaries,
                    waitingSessions = waitingSessions,
                    sessionErrors = sessionErrors,
                    sessionConnectionStates = sessionConnectionStates,
                    sshConnectionState = connectionState,
                    autoConnectEnabled = autoConnectEnabled,
                    onToggleAutoConnect = viewModel::setAutoConnect,
                    displayNames = displayNames
                )
            }
        }
    }
}
