# Claude Mobile - Product Requirements

## What It Is
Android app that provides a mobile interface to Claude CLI via SSH. Connects to a remote machine, manages tmux sessions running Claude, and provides a native chat experience with session management, model selection, and cost tracking.

## Architecture
- Android Kotlin + Jetpack Compose, Material 3
- SSH connection to remote host, manages tmux sessions
- File-based IPC protocol via `/tmp/claude-mobile/{sessionName}/` (status, pending, response, tokens, cost, model files)
- Worker bash script per session handles message polling and Claude CLI interaction
- Self-hosted update server (Python HTTP on port 8888, served via Tailscale)

## Shipped Features (v1.0.0+)

### Connection
- SSH connect via password or key auth
- Biometric unlock for stored credentials
- Auto-connect on launch
- Connection health monitoring with auto-reconnect after 3 failures
- SSH keep-alive (15s interval)

### Sessions
- Create, rename, kill, archive sessions
- Custom and auto-generated session names (Haiku-generated from first message)
- One-shot sessions (auto-archive after completion)
- Session persistence with full message history
- Sync live server sessions with local cache on connect
- Swipe gestures for archive/delete

### Chat
- Real-time messaging with typing indicators
- Status updates with elapsed time for long requests
- Image attachments via SFTP upload
- Clipboard copy on messages
- 10-minute response timeout

### Models & Costs
- Switch between Opus and Sonnet per session
- Model override via natural language ("use sonnet")
- Token tracking (input, output, cache create, cache read)
- Cost display per session in USD

### Archiving
- Archive sessions with auto-generated summaries (Haiku)
- Read-only viewing of archived sessions
- Dismiss to permanently delete

### Updates
- In-app update checks against version JSON
- Download with progress bar and auto-retry
- APK install launch

## Known Issues / Tech Debt
- Update download can fail on slow connections (retry logic exists but limited)
- No push notifications for completed responses
- No multi-server support (single SSH target)
- Session polling is fixed 5s interval (not adaptive)

## Roadmap / Ideas
- Push notifications when Claude finishes responding
- Multiple server connections
- Adaptive polling (faster when waiting, slower when idle)
- Session search/filter
- Markdown rendering in chat messages
- Export session transcripts
- Dark/light theme toggle
- Tablet/landscape layout optimization
