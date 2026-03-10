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

# Git Workflow
- `main` is the stable released branch. Do NOT work directly on main.
- Create `feat/*` or `fix/*` branches for all work. Use descriptive branch names (e.g. `feat/session-search`, `fix/update-download-retry`) — the merge script uses the branch name as the commit title on main.
- To merge to main, run `./merge-to-main.sh` from your feature branch. It uses a file-based mutex so parallel sessions can't merge simultaneously.
- If the lock is stale, use `./merge-to-main.sh --force-unlock`.
- After merging, run `./deploy.sh` to build and deploy.
