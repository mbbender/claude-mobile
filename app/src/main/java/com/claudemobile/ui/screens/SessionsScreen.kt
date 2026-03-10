package com.claudemobile.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.claudemobile.model.ClaudeModel
import com.claudemobile.model.ClaudeSession
import com.claudemobile.model.ConnectionState
import com.claudemobile.model.Project
import com.claudemobile.model.SessionConnectionState
import com.claudemobile.update.UpdateInfo
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    sessions: List<ClaudeSession>,
    onSessionClick: (String) -> Unit,
    onQuickNewInteractive: () -> Unit,
    onNewInteractive: (String) -> Unit,
    onKillSession: (String) -> Unit,
    onArchiveSession: (String) -> Unit = {},
    onArchivedSessionClick: (String) -> Unit = {},
    onDismissArchived: (String) -> Unit = {},
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onRefresh: () -> Unit,
    onCheckUpdate: () -> Unit = {},
    updateInfo: UpdateInfo? = null,
    onInstallUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    checkingUpdate: Boolean = false,
    snackMessage: String? = null,
    onClearSnack: () -> Unit = {},
    versionName: String = "",
    sessionTokens: Map<String, Long> = emptyMap(),
    sessionCosts: Map<String, Double> = emptyMap(),
    sessionModels: Map<String, ClaudeModel> = emptyMap(),
    archivedSessions: Set<String> = emptySet(),
    sessionSummaries: Map<String, String> = emptyMap(),
    waitingSessions: Set<String> = emptySet(),
    sessionErrors: Map<String, String> = emptyMap(),
    sessionConnectionStates: Map<String, SessionConnectionState> = emptyMap(),
    sshConnectionState: ConnectionState = ConnectionState.CONNECTED,
    autoConnectEnabled: Boolean = false,
    onToggleAutoConnect: (Boolean) -> Unit = {},
    displayNames: Map<String, String> = emptyMap(),
    projects: List<Project> = emptyList(),
    selectedProject: Project? = null,
    onSelectProject: (Project?) -> Unit = {},
    onNewProjectSession: (Project) -> Unit = {},
    sessionProjectMap: Map<String, String> = emptyMap()
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var showProjectDropdown by remember { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var fabScale by remember { mutableFloatStateOf(1f) }
    val animatedScale by animateFloatAsState(
        targetValue = fabScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "fabScale"
    )

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackMessage) {
        if (snackMessage != null) {
            snackbarHostState.showSnackbar(snackMessage, duration = SnackbarDuration.Short)
            onClearSnack()
        }
    }

    val isConnected = sshConnectionState == ConnectionState.CONNECTED

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            modifier = Modifier.clickable { showProjectDropdown = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(selectedProject?.name ?: "All Sessions")
                                if (versionName.isNotBlank()) {
                                    Text(
                                        versionName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (projects.isNotEmpty()) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Select project",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showProjectDropdown,
                            onDismissRequest = { showProjectDropdown = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All Sessions") },
                                onClick = {
                                    onSelectProject(null)
                                    showProjectDropdown = false
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            )
                            projects.forEach { project ->
                                DropdownMenuItem(
                                    text = { Text(project.name) },
                                    onClick = {
                                        onSelectProject(project)
                                        showProjectDropdown = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onCheckUpdate, enabled = !checkingUpdate) {
                        if (checkingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.SystemUpdate, contentDescription = "Check for updates")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (isConnected) {
                val fabAction = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch {
                        fabScale = 0.85f
                        kotlinx.coroutines.delay(100)
                        fabScale = 1f
                    }
                    if (selectedProject != null) {
                        onNewProjectSession(selectedProject)
                    } else {
                        onQuickNewInteractive()
                    }
                }
                Box(
                    modifier = Modifier
                        .scale(animatedScale)
                        .combinedClickable(
                            onClick = { fabAction() },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showNameDialog = true
                            }
                        )
                ) {
                    FloatingActionButton(
                        onClick = { fabAction() },
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Session")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection status banner
            if (!isConnected) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (sshConnectionState == ConnectionState.ERROR)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (sshConnectionState == ConnectionState.CONNECTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Connecting to server...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Icon(
                                Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Not connected",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onRefresh) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            // Update banner
            if (updateInfo != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Update available",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "v${updateInfo.versionName} is ready to install",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        TextButton(onClick = onDismissUpdate) {
                            Text("Later")
                        }
                        Button(onClick = onInstallUpdate) {
                            Text("Update")
                        }
                    }
                }
            }

            // Filter sessions by selected project
            val filteredSessions = if (selectedProject != null) {
                sessions.filter { sessionProjectMap[it.name] == selectedProject.path }
            } else {
                sessions
            }
            val filteredArchived = if (selectedProject != null) {
                archivedSessions.filter { sessionProjectMap[it] == selectedProject.path }
            } else {
                archivedSessions
            }

            if (filteredSessions.isEmpty() && filteredArchived.isEmpty() && (selectedProject != null || projects.isEmpty())) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (!isConnected) "Loading sessions..."
                            else if (selectedProject != null) "No sessions in ${selectedProject.name}"
                            else "No active sessions",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isConnected) {
                            Text(
                                "Tap + to start a new session",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedProject == null && projects.isNotEmpty()) {
                        item {
                            Text(
                                "Projects",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        items(projects, key = { "project-${it.path}" }) { project ->
                            ProjectCard(
                                project = project,
                                onClick = { onSelectProject(project) }
                            )
                        }
                        if (filteredSessions.isNotEmpty()) {
                            item {
                                Text(
                                    "Sessions",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                    items(filteredSessions, key = { it.name }) { session ->
                        val connState = sessionConnectionStates[session.name]
                            ?: SessionConnectionState.DISCONNECTED
                        SwipeToRevealCard(
                            onArchive = { onArchiveSession(session.name) },
                            onDelete = { onKillSession(session.name) }
                        ) {
                            SessionCard(
                                session = session,
                                displayName = displayNames[session.name],
                                onClick = { onSessionClick(session.name) },
                                onLongClick = { renameTarget = session.name },
                                tokens = sessionTokens[session.name],
                                cost = sessionCosts[session.name],
                                model = sessionModels[session.name],
                                isWaiting = session.name in waitingSessions,
                                hasError = session.name in sessionErrors,
                                connectionState = connState
                            )
                        }
                    }
                    if (filteredArchived.isNotEmpty()) {
                        item {
                            Text(
                                "Archived",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(filteredArchived.toList().reversed(), key = { "archived-$it" }) { name ->
                            SwipeToRevealCard(
                                onDelete = { onDismissArchived(name) },
                                dismissOnFullSwipe = true
                            ) {
                                ArchivedSessionCard(
                                    name = displayNames[name] ?: name,
                                    tmuxName = name,
                                    tokens = sessionTokens[name],
                                    cost = sessionCosts[name],
                                    model = sessionModels[name],
                                    summary = sessionSummaries[name],
                                    onClick = { onArchivedSessionClick(name) }
                                )
                            }
                        }
                    }
                }
            }
        } // close Column
    }

    if (showNameDialog) {
        NewSessionDialog(
            title = "New Session",
            onDismiss = { showNameDialog = false },
            onConfirm = { name ->
                onNewInteractive(name)
                showNameDialog = false
            }
        )
    }

    renameTarget?.let { tmuxName ->
        RenameSessionDialog(
            currentName = displayNames[tmuxName] ?: tmuxName,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRenameSession(tmuxName, newName)
                renameTarget = null
            }
        )
    }
}

@Composable
private fun SwipeToRevealCard(
    onArchive: (() -> Unit)? = null,
    onDelete: () -> Unit,
    dismissOnFullSwipe: Boolean = false,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "swipeOffset"
    )

    // Button width: archive + delete, or just delete
    val buttonCount = if (onArchive != null) 2 else 1
    val density = LocalDensity.current
    val revealWidth = with(density) { (buttonCount * 72).dp.toPx() }
    val dismissThreshold = revealWidth * 2.5f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        // Background action buttons
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(
                    if (animatedOffsetX < -revealWidth * 1.5f)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onArchive != null) {
                IconButton(
                    onClick = {
                        offsetX = 0f
                        onArchive()
                    },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = "Archive",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = {
                    offsetX = 0f
                    onDelete()
                },
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        // Foreground content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val new = offsetX + delta
                        val maxSwipe = if (dismissOnFullSwipe) -dismissThreshold else -revealWidth
                        offsetX = new.coerceIn(maxSwipe, 0f)
                    },
                    onDragStopped = {
                        if (dismissOnFullSwipe && offsetX < -dismissThreshold * 0.8f) {
                            onDelete()
                        } else {
                            offsetX = if (offsetX < -revealWidth / 2f) -revealWidth else 0f
                        }
                    }
                )
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: ClaudeSession,
    displayName: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    tokens: Long? = null,
    cost: Double? = null,
    model: ClaudeModel? = null,
    isWaiting: Boolean = false,
    hasError: Boolean = false,
    connectionState: SessionConnectionState = SessionConnectionState.CONNECTED
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val isDisconnected = connectionState == SessionConnectionState.DISCONNECTED
    val isReconnecting = connectionState == SessionConnectionState.RECONNECTING

    // Pulse animation for reconnecting state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val baseStatusColor by animateColorAsState(
        when {
            isDisconnected -> MaterialTheme.colorScheme.error
            hasError -> MaterialTheme.colorScheme.error
            isWaiting -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
            isReconnecting -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.outline // Connected but idle (gray)
        },
        label = "status"
    )
    val statusColor = if (isReconnecting) {
        MaterialTheme.colorScheme.outline.copy(alpha = pulseAlpha)
    } else {
        baseStatusColor
    }

    val cardAlpha = if (isDisconnected) 0.5f else 1f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isDisconnected) Icons.Default.WifiOff else Icons.Default.Circle,
                contentDescription = when {
                    isDisconnected -> "Disconnected"
                    isReconnecting -> "Reconnecting"
                    hasError -> "Error"
                    isWaiting -> "Processing"
                    else -> "Connected"
                },
                tint = statusColor,
                modifier = Modifier.size(12.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName ?: session.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
                )
                val modelTag = model?.displayName ?: "Opus"
                val statusText = when {
                    isDisconnected -> "Disconnected"
                    isReconnecting -> "Reconnecting..."
                    else -> {
                        if (tokens != null && tokens > 0) {
                            val formatted = if (tokens >= 1000) "${tokens / 1000}k" else "$tokens"
                            val costStr = if (cost != null && cost > 0) " · $${"%.2f".format(cost)}" else ""
                            "$modelTag · $formatted tokens$costStr"
                        } else {
                            modelTag
                        }
                    }
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDisconnected) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (displayName != null && displayName != session.name) {
                    Text(
                        text = session.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

        }
    }
}

@Composable
private fun ArchivedSessionCard(
    name: String,
    tmuxName: String = name,
    tokens: Long? = null,
    cost: Double? = null,
    model: ClaudeModel? = null,
    summary: String? = null,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Completed",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(12.dp).padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (tmuxName != name) {
                    Text(
                        text = tmuxName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                val modelTag = model?.displayName ?: "Opus"
                val info = if (tokens != null && tokens > 0) {
                    val formatted = if (tokens >= 1000) "${tokens / 1000}k" else "$tokens"
                    val costStr = if (cost != null && cost > 0) " · $${"%.2f".format(cost)}" else ""
                    "$modelTag · $formatted tokens$costStr"
                } else {
                    modelTag
                }
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

        }
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = project.path.replace(Regex("^/home/[^/]+"), "~"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open project",
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun NewSessionDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Session name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("my-task") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RenameSessionDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Session name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name != currentName
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
