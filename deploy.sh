#!/bin/bash
set -e

cd "$(dirname "$0")"

# Read current version
CURRENT_CODE=$(grep 'versionCode' app/build.gradle.kts | head -1 | sed 's/[^0-9]//g')
CURRENT_NAME=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')

echo "Current version: $CURRENT_NAME (code $CURRENT_CODE)"

# Bump version
if [ "$1" = "--no-bump" ]; then
    NEW_CODE=$CURRENT_CODE
    NEW_NAME=$CURRENT_NAME
else
    NEW_CODE=$((CURRENT_CODE + 1))
    # Auto-increment minor version (2.5 -> 2.6)
    MAJOR=$(echo "$CURRENT_NAME" | cut -d. -f1)
    MINOR=$(echo "$CURRENT_NAME" | cut -d. -f2)
    NEW_MINOR=$((MINOR + 1))
    NEW_NAME="$MAJOR.$NEW_MINOR"

    sed -i '' "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/" app/build.gradle.kts
    sed -i '' "s/versionName = \"$CURRENT_NAME\"/versionName = \"$NEW_NAME\"/" app/build.gradle.kts
    echo "Bumped to: $NEW_NAME (code $NEW_CODE)"
fi

# Build
echo "Building debug APK..."
./gradlew assembleDebug 2>&1 | tail -3

APK_PATH=app/build/outputs/apk/debug/app-debug.apk
if [ ! -f "$APK_PATH" ]; then
    echo "ERROR: APK not found at $APK_PATH"
    exit 1
fi

# Find Tailscale IP
TAILSCALE_IP=$(ifconfig | grep -A1 utun | grep 'inet ' | awk '{print $2}' | head -1)
if [ -z "$TAILSCALE_IP" ]; then
    echo "ERROR: Could not find Tailscale IP"
    exit 1
fi

# Deploy to local update server
SERVE_DIR="/private/tmp"
cp "$APK_PATH" "$SERVE_DIR/claude-mobile.apk"
cat > "$SERVE_DIR/claude-mobile-version.json" << EOF
{"versionCode": $NEW_CODE, "versionName": "$NEW_NAME", "url": "http://$TAILSCALE_IP:8888/claude-mobile.apk"}
EOF

# Ensure HTTP server is running
if ! lsof -i :8888 >/dev/null 2>&1; then
    echo "Starting HTTP server on port 8888..."
    cd "$SERVE_DIR"
    python3 -c "
import http.server, socketserver
class NoCacheHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0')
        self.send_header('Pragma', 'no-cache')
        self.send_header('Expires', '0')
        super().end_headers()
class ThreadedServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
with ThreadedServer(('0.0.0.0', 8888), NoCacheHandler) as httpd:
    httpd.serve_forever()
" &
    disown
    sleep 1
    cd "$(dirname "$0")"
fi

echo "Deployed v$NEW_NAME (code $NEW_CODE) to update server"

# Verify
curl -s "http://$TAILSCALE_IP:8888/claude-mobile-version.json"
echo ""
