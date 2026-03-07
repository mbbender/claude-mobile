package com.claudemobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.claudemobile.model.ConnectionState
import com.claudemobile.model.SshConfig
import com.claudemobile.update.UpdateInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    connectionState: ConnectionState,
    errorMessage: String?,
    savedConfig: SshConfig,
    onConnect: (SshConfig) -> Unit,
    onDismissError: () -> Unit,
    showBiometric: Boolean = false,
    onBiometricLogin: () -> Unit = {},
    updateInfo: UpdateInfo? = null,
    onInstallUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {}
) {
    var host by remember { mutableStateOf(savedConfig.host) }
    var port by remember { mutableStateOf(savedConfig.port.toString()) }
    var username by remember { mutableStateOf(savedConfig.username) }
    var password by remember { mutableStateOf(savedConfig.password) }
    var keyPath by remember { mutableStateOf(savedConfig.keyPath) }
    var showPassword by remember { mutableStateOf(false) }
    var useKey by remember { mutableStateOf(savedConfig.keyPath.isNotBlank()) }

    // Auto-trigger biometric only if credentials existed at screen creation (not mid-login)
    val hadCredentialsOnMount = remember { showBiometric }
    var biometricTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (hadCredentialsOnMount && !biometricTriggered) {
            biometricTriggered = true
            onBiometricLogin()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Update banner
        if (updateInfo != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Update v${updateInfo.versionName} available",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    TextButton(onClick = onDismissUpdate) { Text("Later") }
                    Button(onClick = onInstallUpdate) { Text("Update") }
                }
            }
        }

        Icon(
            imageVector = Icons.Default.Terminal,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Claude Mobile",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "Connect to your server via SSH",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        AnimatedVisibility(visible = errorMessage != null) {
            errorMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onDismissError) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }

        // Biometric quick-login button
        if (showBiometric) {
            OutlinedButton(
                onClick = onBiometricLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(bottom = 4.dp),
                enabled = connectionState != ConnectionState.CONNECTING
            ) {
                Icon(
                    Icons.Default.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Quick Connect with Biometrics")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    "or enter manually",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host (Tailscale IP or hostname)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.width(100.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Use SSH Key", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = useKey, onCheckedChange = { useKey = it })
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (useKey) {
            OutlinedTextField(
                value = keyPath,
                onValueChange = { keyPath = it },
                label = { Text("Private key path on device") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("/storage/emulated/0/.ssh/id_ed25519") }
            )
        } else {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle password"
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                onConnect(
                    SshConfig(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        username = username.trim(),
                        password = if (useKey) "" else password,
                        keyPath = if (useKey) keyPath.trim() else ""
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = connectionState != ConnectionState.CONNECTING
                && host.isNotBlank()
                && username.isNotBlank()
        ) {
            if (connectionState == ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connecting...")
            } else {
                Text("Connect")
            }
        }
    }
}
