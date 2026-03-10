#!/bin/bash
set -e

cd "$(dirname "$0")"

LOCK_FILE="/tmp/claude-mobile-merge.lock"

# Force unlock
if [ "$1" = "--force-unlock" ]; then
    if [ -d "$LOCK_FILE" ]; then
        OWNER=$(cat "$LOCK_FILE/owner" 2>/dev/null || echo "unknown")
        rm -rf "$LOCK_FILE"
        echo "Force-removed stale lock (was held by: $OWNER)"
    else
        echo "No lock to remove."
    fi
    exit 0
fi

BRANCH=$(git branch --show-current)

# Validate
if [ "$BRANCH" = "main" ]; then
    echo "ERROR: Already on main. Switch to a feature branch first."
    exit 1
fi

# Acquire lock (atomic via mkdir)
acquire_lock() {
    local max_wait=60
    local waited=0
    while ! mkdir "$LOCK_FILE" 2>/dev/null; do
        OWNER=$(cat "$LOCK_FILE/owner" 2>/dev/null || echo "unknown")
        if [ $waited -eq 0 ]; then
            echo "Waiting for lock held by: $OWNER"
        fi
        sleep 2
        waited=$((waited + 2))
        if [ $waited -ge $max_wait ]; then
            echo "ERROR: Timed out waiting for merge lock (held by $OWNER for ${max_wait}s)"
            echo "If stale, remove manually: rm -rf $LOCK_FILE"
            exit 1
        fi
    done
    echo "$BRANCH (pid $$)" > "$LOCK_FILE/owner"
    trap release_lock EXIT INT TERM
    echo "Lock acquired for $BRANCH"
}

release_lock() {
    rm -rf "$LOCK_FILE"
}

acquire_lock

echo "Merging $BRANCH into main..."

# Fetch latest main
git fetch origin main 2>/dev/null || true

# Switch to main and merge
git checkout main
git pull origin main 2>/dev/null || true

if git merge --squash "$BRANCH"; then
    # Build a descriptive commit message from the branch name and all commits
    BRANCH_DESC=$(echo "$BRANCH" | sed 's|^feat/||;s|^fix/||' | tr '-' ' ' | tr '_' ' ')
    ALL_COMMITS=$(git log main.."$BRANCH" --pretty=format:"- %s" 2>/dev/null)
    COMMIT_MSG="$BRANCH_DESC

Squash merge from $BRANCH

Changes:
$ALL_COMMITS

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
    git commit -m "$COMMIT_MSG"
    echo "Merged $BRANCH into main successfully."
    echo ""
    echo "Next steps:"
    echo "  ./deploy.sh        # build and deploy"
    echo "  git branch -d $BRANCH  # delete the branch"
else
    echo "CONFLICT detected. Resolve conflicts, then:"
    echo "  git add ."
    echo "  git commit"
    echo "  The lock will release when this script exits."
fi
