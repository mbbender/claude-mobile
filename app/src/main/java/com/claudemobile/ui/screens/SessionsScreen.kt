package com.claudemobile.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudemobile.model.ClaudeModel
import com.claudemobile.model.ClaudeSession
import com.claudemobile.update.UpdateInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    sessions: List<ClaudeSession>,
    onSessionClick: (String) -> Unit,
    onQuickNewInteractive: (ClaudeModel) -> Unit,
    onNewInteractive: (String, ClaudeModel) -> Unit,
    onNewTask: (String, String, ClaudeModel) -> Unit,
    onKillSession: (String) -> Unit,
    onArchiveSession: (String) -> Unit = {},
    onDismissArchived: (String) -> Unit = {},
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
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
    archivedSessions: Set<String> = emptySet()
) {
    var showNameDialog by remember { mutableStateOf(false) }
    var showTaskDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf(ClaudeModel.OPUS) }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sessions")
                        if (versionName.isNotBlank()) {
                            Text(
                                "v$versionName",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh sessions")
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Default.LinkOff, contentDescription = "Disconnect")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Model toggle
                SmallFloatingActionButton(
                    onClick = {
                        selectedModel = if (selectedModel == ClaudeModel.OPUS) ClaudeModel.SONNET else ClaudeModel.OPUS
                    },
                    containerColor = if (selectedModel == ClaudeModel.OPUS)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        if (selectedModel == ClaudeModel.OPUS) "O" else "S",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedModel == ClaudeModel.OPUS)
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                SmallFloatingActionButton(
                    onClick = { showTaskDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.Assignment, contentDescription = "New Task")
                }
                Box(
                    modifier = Modifier
                        .scale(animatedScale)
                        .combinedClickable(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    fabScale = 0.85f
                                    kotlinx.coroutines.delay(100)
                                    fabScale = 1f
                                }
                                onQuickNewInteractive(selectedModel)
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showNameDialog = true
                            }
                        )
                ) {
                    FloatingActionButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                fabScale = 0.85f
                                kotlinx.coroutines.delay(100)
                                fabScale = 1f
                            }
                            onQuickNewInteractive(selectedModel)
                        },
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

            if (sessions.isEmpty() && archivedSessions.isEmpty()) {
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
                            "No active sessions",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Tap + to start a new Claude session",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Long-press + to set a custom name",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.name }) { session ->
                        SessionCard(
                            session = session,
                            onClick = { onSessionClick(session.name) },
                            onKill = { onKillSession(session.name) },
                            onArchive = { onArchiveSession(session.name) },
                            onLongClick = { renameTarget = session.name },
                            tokens = sessionTokens[session.name],
                            cost = sessionCosts[session.name],
                            model = sessionModels[session.name]
                        )
                    }
                    if (archivedSessions.isNotEmpty()) {
                        item {
                            Text(
                                "Completed",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(archivedSessions.toList(), key = { "archived-$it" }) { name ->
                            ArchivedSessionCard(
                                name = name,
                                tokens = sessionTokens[name],
                                cost = sessionCosts[name],
                                model = sessionModels[name],
                                onDismiss = { onDismissArchived(name) }
                            )
                        }
                    }
                }
            }
        } // close Column
    }

    if (showNameDialog) {
        NewSessionDialog(
            title = "New Interactive Session",
            showPrompt = false,
            initialModel = selectedModel,
            onDismiss = { showNameDialog = false },
            onConfirm = { name, _, model ->
                onNewInteractive(name, model)
                showNameDialog = false
            }
        )
    }

    if (showTaskDialog) {
        NewSessionDialog(
            title = "New Task",
            showPrompt = true,
            initialModel = selectedModel,
            onDismiss = { showTaskDialog = false },
            onConfirm = { name, prompt, model ->
                onNewTask(name, prompt, model)
                showTaskDialog = false
            }
        )
    }

    renameTarget?.let { oldName ->
        RenameSessionDialog(
            currentName = oldName,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                onRenameSession(oldName, newName)
                renameTarget = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: ClaudeSession,
    onClick: () -> Unit,
    onKill: () -> Unit,
    onArchive: () -> Unit = {},
    onLongClick: () -> Unit = {},
    tokens: Long? = null,
    cost: Double? = null,
    model: ClaudeModel? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val statusColor by animateColorAsState(
        if (session.isRunning) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline,
        label = "status"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
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
                Icons.Default.Circle,
                contentDescription = if (session.isRunning) "Running" else "Stopped",
                tint = statusColor,
                modifier = Modifier.size(12.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
                )
                val modelTag = model?.displayName ?: "Opus"
                val tokenInfo = if (tokens != null && tokens > 0) {
                    val formatted = if (tokens >= 1000) "${tokens / 1000}k" else "$tokens"
                    val costStr = if (cost != null && cost > 0) " · $${"%.2f".format(cost)}" else ""
                    "$modelTag · $formatted tokens$costStr"
                } else {
                    "$modelTag · ${session.windowId} · ${session.lastOutput}"
                }
                Text(
                    text = tokenInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onArchive) {
                Icon(
                    Icons.Default.Archive,
                    contentDescription = "Archive",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onKill) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ArchivedSessionCard(
    name: String,
    tokens: Long? = null,
    cost: Double? = null,
    model: ClaudeModel? = null,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Completed",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(12.dp)
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
                val modelTag = model?.displayName ?: "Opus"
                val info = if (tokens != null && tokens > 0) {
                    val formatted = if (tokens >= 1000) "${tokens / 1000}k" else "$tokens"
                    val costStr = if (cost != null && cost > 0) " · $${"%.2f".format(cost)}" else ""
                    "$modelTag · $formatted tokens$costStr · Completed"
                } else {
                    "$modelTag · Completed"
                }
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun NewSessionDialog(
    title: String,
    showPrompt: Boolean,
    initialModel: ClaudeModel = ClaudeModel.OPUS,
    onDismiss: () -> Unit,
    onConfirm: (name: String, prompt: String, model: ClaudeModel) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(initialModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("my-task") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ClaudeModel.entries.forEach { m ->
                        FilterChip(
                            selected = model == m,
                            onClick = { model = m },
                            label = { Text(m.displayName) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (showPrompt) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("Prompt for Claude") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        maxLines = 8,
                        placeholder = { Text("Describe what you want Claude to do...") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, prompt, model) },
                enabled = name.isNotBlank() && (!showPrompt || prompt.isNotBlank())
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
