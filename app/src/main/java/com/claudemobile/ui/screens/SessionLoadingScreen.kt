package com.claudemobile.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val loadingMessages = listOf(
    "Waking up Claude...",
    "Brewing some intelligence...",
    "Warming up the neurons...",
    "Summoning the AI overlord...",
    "Untangling the neural nets...",
    "Feeding the hamsters...",
    "Compiling thoughts...",
    "Sharpening the tokens...",
    "Booting up the brain...",
    "Calibrating sarcasm levels..."
)

private val reconnectMessages = listOf(
    "Dusting off the conversation...",
    "Waking Claude back up...",
    "Picking up where we left off...",
    "Reheating your session...",
    "Untangling the threads...",
    "Digging through the archives...",
    "Claude missed you too...",
    "Reconnecting brain cells...",
    "Finding your place in the chat...",
    "Getting the band back together..."
)

private val updateMessages = listOf(
    "Polishing the new bits...",
    "Teaching Claude new tricks...",
    "Upgrading the hamster wheels...",
    "Downloading more brain cells...",
    "Reticulating splines...",
    "Adding extra sparkle...",
    "Leveling up your experience...",
    "Tightening the bolts...",
    "Fresh pixels incoming...",
    "Making things even better..."
)

@Composable
fun ReconnectLoadingScreen() {
    LoadingScreenContent(
        messages = reconnectMessages,
        icon = "\uD83D\uDD04",
        subtitle = "Reconnecting session"
    )
}

@Composable
fun UpdateLoadingScreen() {
    LoadingScreenContent(
        messages = updateMessages,
        icon = "\u2B06",
        subtitle = "Downloading update"
    )
}

@Composable
fun SessionLoadingScreen() {
    LoadingScreenContent(
        messages = loadingMessages,
        icon = ">_",
        subtitle = "Setting up session"
    )
}

@Composable
private fun LoadingScreenContent(
    messages: List<String>,
    icon: String,
    subtitle: String
) {
    val message = remember { messages.random() }

    // Bouncing brain emoji
    val infiniteTransition = rememberInfiniteTransition(label = "loading")

    val bounce by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    val wobble by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wobble"
    )

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Dots animation
    var dotCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            dotCount = (dotCount + 1) % 4
        }
    }
    val dots = ".".repeat(dotCount)

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Animated icon
        Text(
            text = icon,
            fontSize = 56.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .offset(y = (-20 * bounce).dp)
                .rotate(wobble)
                .scale(pulse)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "$subtitle$dots",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private val EaseInOutCubic: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val EaseInOutSine: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
