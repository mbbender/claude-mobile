package com.claudemobile.ssh

import com.claudemobile.model.ClaudeModel
import com.claudemobile.model.ClaudeSession
import com.claudemobile.model.SshConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Properties

private fun shellEscape(s: String): String {
    return "'" + s.replace("'", "'\\''") + "'"
}

class SshManager {
    private var session: Session? = null
    private var lastConfig: SshConfig? = null
    private val jsch = JSch()
    private var identityAdded = false
    private val execMutex = Mutex()

    val isConnected: Boolean get() = session?.isConnected == true

    suspend fun connect(config: SshConfig) = withContext(Dispatchers.IO) {
        disconnect()
        lastConfig = config

        if (config.keyPath.isNotBlank() && !identityAdded) {
            jsch.addIdentity(config.keyPath)
            identityAdded = true
        }

        session = jsch.getSession(config.username, config.host, config.port).apply {
            if (config.password.isNotBlank()) {
                setPassword(config.password)
            }
            val props = Properties()
            props["StrictHostKeyChecking"] = "no"
            setConfig(props)
            timeout = 10_000
            setServerAliveInterval(15_000)
            setServerAliveCountMax(3)
            connect()
        }
    }

    suspend fun reconnect(): Boolean = withContext(Dispatchers.IO) {
        val config = lastConfig ?: return@withContext false
        try {
            disconnect()
            connect(config)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun ensureConnected() {
        if (session?.isConnected != true) {
            if (!reconnect()) {
                throw IllegalStateException("Not connected")
            }
        }
    }

    fun disconnect() {
        session?.disconnect()
        session = null
    }

    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        execMutex.withLock {
            ensureConnected()
            val s = session ?: throw IllegalStateException("Not connected")
            val channel = try {
                s.openChannel("exec") as ChannelExec
            } catch (e: Exception) {
                // Channel open failed — try reconnect once
                if (reconnect()) {
                    val s2 = session ?: throw IllegalStateException("Not connected")
                    s2.openChannel("exec") as ChannelExec
                } else {
                    throw e
                }
            }
            val output = ByteArrayOutputStream()
            val errOutput = ByteArrayOutputStream()
            channel.outputStream = output
            channel.setErrStream(errOutput)
            channel.setCommand("/bin/bash -lc ${shellEscape(command)}")
            channel.connect(15_000)

            while (!channel.isClosed) {
                Thread.sleep(100)
            }
            channel.disconnect()

            val result = output.toString("UTF-8")
            val err = errOutput.toString("UTF-8")
            if (result.isBlank() && err.isNotBlank()) err else result
        }
    }

    suspend fun listSessions(): List<ClaudeSession> = withContext(Dispatchers.IO) {
        val raw = exec("tmux list-sessions -F '#{session_name}:#{session_attached}:#{pane_current_command}' 2>/dev/null")

        raw.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.trim().split(":")
                val name = parts.getOrElse(0) { "unknown" }
                val attached = parts.getOrElse(1) { "0" } == "1"
                val command = parts.getOrElse(2) { "" }
                ClaudeSession(
                    name = name,
                    isRunning = true,
                    windowId = if (attached) "attached" else "detached",
                    lastOutput = command
                )
            }
    }

    suspend fun ensureMobileWorkspace() = withContext(Dispatchers.IO) {
        exec("mkdir -p ~/Claude/claude-mobile")
        exec("""cat > ~/Claude/claude-mobile/CLAUDE.md << 'MEOF'
# Mobile Mode

You are being accessed from a mobile phone app (Claude Mobile).
Keep responses short and conversational — like a text message, not a terminal dump.

Rules:
- Be brief. Lead with the answer. Skip preamble.
- No code fences unless specifically asked for code.
- No bullet lists longer than 3-4 items.
- When you run tools or edit files, just say what you did in one sentence. Don't show the full output.
- If showing code, keep snippets short (under 10 lines). Say "I updated the file" rather than showing the whole diff.
- Use plain language. Avoid markdown headers and horizontal rules.
- When asked to do a task, do it and confirm briefly.
MEOF""")
    }

    suspend fun setupMobileSession(sessionName: String, model: ClaudeModel = ClaudeModel.OPUS) = withContext(Dispatchers.IO) {
        val dir = "/tmp/claude-mobile/$sessionName"
        exec("mkdir -p '$dir'")
        exec("echo 'ready' > '$dir/status'")
        exec("echo '${model.id}' > '$dir/model'")

        // Write the worker script — use ${'$'} for bash $ to avoid Kotlin interpolation
        val d = "${'$'}"
        exec("echo '0' > '$dir/tokens'")
        exec("""cat > '$dir/worker.sh' << 'WORKEREOF'
#!/bin/bash
cd ~/Claude/claude-mobile
DIR="$dir"
MODEL="${model.id}"
FIRST=true
TOTAL_TOKENS=0
while true; do
  while [ ! -f "${d}DIR/pending" ]; do sleep 0.5; done
  PROMPT="${d}(cat "${d}DIR/pending")"
  rm -f "${d}DIR/pending"
  echo "${d}PROMPT" > "${d}DIR/last_prompt"
  echo 'thinking' > "${d}DIR/status"
  if [ "${d}FIRST" = true ]; then
    claude -p "${d}PROMPT" --model "${d}MODEL" --dangerously-skip-permissions --output-format json > "${d}DIR/raw_response" 2>"${d}DIR/progress"
    FIRST=false
  else
    claude -p "${d}PROMPT" --continue --model "${d}MODEL" --dangerously-skip-permissions --output-format json > "${d}DIR/raw_response" 2>"${d}DIR/progress"
  fi
  # Parse JSON response — extract text and tokens
  if jq -e '.result' "${d}DIR/raw_response" > /dev/null 2>&1; then
    jq -r '.result // empty' "${d}DIR/raw_response" > "${d}DIR/response"
    IN=${d}(jq -r '.usage.input_tokens // 0' "${d}DIR/raw_response")
    OUT=${d}(jq -r '.usage.output_tokens // 0' "${d}DIR/raw_response")
    CACHE=${d}(jq -r '.usage.cache_creation_input_tokens // 0' "${d}DIR/raw_response")
    CACHE_READ=${d}(jq -r '.usage.cache_read_input_tokens // 0' "${d}DIR/raw_response")
    COST=${d}(jq -r '.total_cost_usd // 0' "${d}DIR/raw_response")
    TURN_TOKENS=${d}((IN + OUT + CACHE + CACHE_READ))
    TOTAL_TOKENS=${d}((TOTAL_TOKENS + TURN_TOKENS))
    echo "${d}TOTAL_TOKENS" > "${d}DIR/tokens"
    echo "${d}COST" > "${d}DIR/cost"
  else
    # Not JSON — raw output (error etc)
    cp "${d}DIR/raw_response" "${d}DIR/response"
  fi
  echo 'ready' > "${d}DIR/status"
done
WORKEREOF""")
        exec("chmod +x '$dir/worker.sh'")
        exec("tmux new-session -d -s '$sessionName' '$dir/worker.sh'")
    }

    data class ReconnectResult(
        val dataDir: String,
        val lastResponse: String?,
        val lastPrompt: String?,
        val isThinking: Boolean,
        val tokens: Long,
        val cost: Double,
        val model: ClaudeModel = ClaudeModel.OPUS
    )

    suspend fun reconnectSession(tmuxName: String): ReconnectResult = withContext(Dispatchers.IO) {
        // Find data dir — scan /tmp/claude-mobile/ for existing dirs
        val dataDir = resolveDataDir(tmuxName)

        // Check if worker is still running by looking at pane command
        val paneCmd = try {
            exec("tmux display-message -t '$tmuxName' -p '#{pane_current_command}' 2>/dev/null").trim()
        } catch (_: Exception) { "bash" }

        val dir = "/tmp/claude-mobile/$dataDir"

        // Read stored model or default to opus
        val modelId = try {
            exec("cat '$dir/model' 2>/dev/null").trim().ifBlank { ClaudeModel.OPUS.id }
        } catch (_: Exception) { ClaudeModel.OPUS.id }
        val model = ClaudeModel.fromId(modelId)

        if (paneCmd == "bash" || paneCmd.isBlank()) {
            // Worker is dead — rewrite and restart it
            ensureMobileWorkspace()
            exec("mkdir -p '$dir'")
            exec("echo 'ready' > '$dir/status'")

            val d = "${'$'}"
            exec("echo '${exec("cat '$dir/tokens' 2>/dev/null").trim().ifBlank { "0" }}' > '$dir/tokens'")
            exec("""cat > '$dir/worker.sh' << 'WORKEREOF'
#!/bin/bash
cd ~/Claude/claude-mobile
DIR="$dir"
MODEL="${model.id}"
FIRST=false
TOTAL_TOKENS=${exec("cat '$dir/tokens' 2>/dev/null").trim().ifBlank { "0" }}
while true; do
  while [ ! -f "${d}DIR/pending" ]; do sleep 0.5; done
  PROMPT="${d}(cat "${d}DIR/pending")"
  rm -f "${d}DIR/pending"
  echo "${d}PROMPT" > "${d}DIR/last_prompt"
  echo 'thinking' > "${d}DIR/status"
  claude -p "${d}PROMPT" --continue --model "${d}MODEL" --dangerously-skip-permissions --output-format json > "${d}DIR/raw_response" 2>"${d}DIR/progress"
  if jq -e '.result' "${d}DIR/raw_response" > /dev/null 2>&1; then
    jq -r '.result // empty' "${d}DIR/raw_response" > "${d}DIR/response"
    IN=${d}(jq -r '.usage.input_tokens // 0' "${d}DIR/raw_response")
    OUT=${d}(jq -r '.usage.output_tokens // 0' "${d}DIR/raw_response")
    CACHE=${d}(jq -r '.usage.cache_creation_input_tokens // 0' "${d}DIR/raw_response")
    CACHE_READ=${d}(jq -r '.usage.cache_read_input_tokens // 0' "${d}DIR/raw_response")
    COST=${d}(jq -r '.total_cost_usd // 0' "${d}DIR/raw_response")
    TURN_TOKENS=${d}((IN + OUT + CACHE + CACHE_READ))
    TOTAL_TOKENS=${d}((TOTAL_TOKENS + TURN_TOKENS))
    echo "${d}TOTAL_TOKENS" > "${d}DIR/tokens"
    echo "${d}COST" > "${d}DIR/cost"
  else
    cp "${d}DIR/raw_response" "${d}DIR/response"
  fi
  echo 'ready' > "${d}DIR/status"
done
WORKEREOF""")
            exec("chmod +x '$dir/worker.sh'")
            // Send the worker script to the existing tmux session
            exec("tmux send-keys -t '$tmuxName' '$dir/worker.sh' Enter")
        }

        // Check current status
        val status = try {
            exec("cat '$dir/status' 2>/dev/null").trim()
        } catch (_: Exception) { "unknown" }
        val isThinking = status == "thinking"

        // Load last prompt
        val lastPrompt = try {
            val p = exec("cat '$dir/last_prompt' 2>/dev/null").trim()
            p.ifBlank { null }
        } catch (_: Exception) { null }

        // Load last response and tokens
        val lastResponse = if (!isThinking) {
            try {
                val r = exec("cat '$dir/response' 2>/dev/null").trim()
                r.ifBlank { null }
            } catch (_: Exception) { null }
        } else null

        val tokens = try {
            exec("cat '$dir/tokens' 2>/dev/null").trim().toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }

        val cost = try {
            exec("cat '$dir/cost' 2>/dev/null").trim().toDoubleOrNull() ?: 0.0
        } catch (_: Exception) { 0.0 }

        ReconnectResult(dataDir, lastResponse, lastPrompt, isThinking, tokens, cost, model)
    }

    suspend fun sendMobileMessage(sessionName: String, message: String) = withContext(Dispatchers.IO) {
        val dir = "/tmp/claude-mobile/$sessionName"
        val escaped = message.replace("'", "'\\''")
        // Clear old response and set status to "pending" BEFORE writing pending file
        // This closes the race window where status is still "ready" from the previous turn
        exec("rm -f '$dir/response' '$dir/raw_response'; echo 'pending' > '$dir/status'; echo '$escaped' > '$dir/pending'")
    }

    suspend fun resolveDataDir(sessionName: String): String = withContext(Dispatchers.IO) {
        // Check if a directory with this exact name exists
        val check = exec("test -d '/tmp/claude-mobile/$sessionName' && echo 'yes' || echo 'no'").trim()
        if (check == "yes") return@withContext sessionName

        // Otherwise, look for the dir that has a worker.sh running under this tmux session
        // by listing all data dirs and checking which tmux session they belong to
        val dirs = exec("ls /tmp/claude-mobile/ 2>/dev/null").trim()
        if (dirs.isNotBlank()) {
            for (dir in dirs.lines()) {
                val trimmed = dir.trim()
                if (trimmed.isNotBlank()) return@withContext trimmed
            }
        }
        sessionName
    }

    suspend fun getMobileStatus(sessionName: String): String = withContext(Dispatchers.IO) {
        try {
            exec("cat /tmp/claude-mobile/$sessionName/status 2>/dev/null").trim()
        } catch (e: Exception) {
            "unknown"
        }
    }

    suspend fun getAllSessionStatuses(sessionNames: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (sessionNames.isEmpty()) return@withContext emptyMap()
        try {
            val cmd = sessionNames.joinToString("; ") { name ->
                "echo \"$name:\$(cat /tmp/claude-mobile/$name/status 2>/dev/null || echo unknown)\""
            }
            val raw = exec(cmd)
            raw.lines().filter { it.contains(":") }.associate { line ->
                val parts = line.split(":", limit = 2)
                parts[0] to (parts.getOrNull(1)?.trim() ?: "unknown")
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun getMobileTokens(sessionName: String): Pair<Long, Double> = withContext(Dispatchers.IO) {
        try {
            val tokens = exec("cat /tmp/claude-mobile/$sessionName/tokens 2>/dev/null").trim().toLongOrNull() ?: 0L
            val cost = exec("cat /tmp/claude-mobile/$sessionName/cost 2>/dev/null").trim().toDoubleOrNull() ?: 0.0
            Pair(tokens, cost)
        } catch (e: Exception) {
            Pair(0L, 0.0)
        }
    }

    suspend fun getMobileProgress(sessionName: String): String? = withContext(Dispatchers.IO) {
        try {
            // Read stderr progress file for brief status
            val progress = exec("tail -5 /tmp/claude-mobile/$sessionName/progress 2>/dev/null").trim()
            if (progress.isNotBlank()) return@withContext progress.lines().last().trim().take(100)
            null
        } catch (_: Exception) { null }
    }

    suspend fun getMobileResponse(sessionName: String): String = withContext(Dispatchers.IO) {
        try {
            exec("cat /tmp/claude-mobile/$sessionName/response 2>/dev/null").trim()
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun renameSession(oldName: String, newName: String): String = withContext(Dispatchers.IO) {
        val sanitized = newName.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().take(40)
        // Only rename the tmux session — keep the data directory at the original path
        exec("tmux rename-session -t '$oldName' '$sanitized' 2>/dev/null")
        sanitized
    }

    suspend fun getHostname(): String = withContext(Dispatchers.IO) {
        try {
            exec("hostname -s").trim()
        } catch (e: Exception) {
            "unknown"
        }
    }

    suspend fun killSession(sessionName: String, dataDir: String = sessionName) = withContext(Dispatchers.IO) {
        exec("tmux kill-session -t '$sessionName' 2>/dev/null")
        exec("rm -rf '/tmp/claude-mobile/$dataDir' 2>/dev/null")
        exec("rm -f '/tmp/claude-$dataDir.log' 2>/dev/null")
    }

    suspend fun startInteractiveSession(name: String, model: ClaudeModel = ClaudeModel.OPUS): String = withContext(Dispatchers.IO) {
        val sanitizedName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        ensureMobileWorkspace()
        setupMobileSession(sanitizedName, model)
        sanitizedName
    }

    suspend fun getSessionModel(sessionName: String): ClaudeModel = withContext(Dispatchers.IO) {
        try {
            val id = exec("cat /tmp/claude-mobile/$sessionName/model 2>/dev/null").trim()
            if (id.isNotBlank()) ClaudeModel.fromId(id) else ClaudeModel.OPUS
        } catch (_: Exception) { ClaudeModel.OPUS }
    }
}
