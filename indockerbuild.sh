#!/usr/bin/env bash
# Compiles the app inside the build container and leaves the APK on the host via
# the volume mount. Usage: ./indockerbuild.sh [debug|release]
set -euo pipefail

BUILD_TYPE="${1:-debug}"
IMAGE_NAME="simple-video-player-builder:latest"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE_DIR="$SCRIPT_DIR/.gradle-cache"

case "$BUILD_TYPE" in
  debug) GRADLE_TASK="assembleDebug" ;;
  release) GRADLE_TASK="assembleRelease" ;;
  *) echo "Unknown build type: $BUILD_TYPE (use debug or release)"; exit 1 ;;
esac

mkdir -p "$CACHE_DIR"

docker run --rm \
  -v "$SCRIPT_DIR:/workspace" \
  -v "$CACHE_DIR:/home/builder/.gradle" \
  -w /workspace \
  "$IMAGE_NAME" \
  bash -c "gradle wrapper --gradle-version 8.9 --no-daemon >/dev/null 2>&1 || true; gradle $GRADLE_TASK --no-daemon --stacktrace"

echo ""
echo "APK output:"
find "$SCRIPT_DIR/app/build/outputs/apk" -name "*.apk" 2>/dev/null || echo "  (no APK found)"
