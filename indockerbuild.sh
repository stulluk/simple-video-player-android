#!/usr/bin/env bash
# Compiles the app inside the build container and leaves the APK on the host
# via the volume mount.
#
# Usage: ./indockerbuild.sh [debug|release] [play|fdroid]
#
# Examples:
#   ./indockerbuild.sh                # play debug (default)
#   ./indockerbuild.sh debug fdroid   # F-Droid debug build
#   ./indockerbuild.sh release play   # Play Store release build
set -euo pipefail

BUILD_TYPE="${1:-debug}"
FLAVOR="${2:-play}"
IMAGE_NAME="simple-video-player-builder:latest"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CACHE_DIR="$SCRIPT_DIR/.gradle-cache"
ANDROID_USER_DIR="$SCRIPT_DIR/.android-cache"

case "$BUILD_TYPE" in
  debug|release) ;;
  *) echo "Unknown build type: $BUILD_TYPE (use debug or release)"; exit 1 ;;
esac

case "$FLAVOR" in
  play|fdroid) ;;
  *) echo "Unknown flavor: $FLAVOR (use play or fdroid)"; exit 1 ;;
esac

# Capitalise via parameter expansion for the Gradle task name (e.g. "Play").
FLAVOR_CAP="$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}"
TYPE_CAP="$(tr '[:lower:]' '[:upper:]' <<< "${BUILD_TYPE:0:1}")${BUILD_TYPE:1}"
GRADLE_TASK="assemble${FLAVOR_CAP}${TYPE_CAP}"

mkdir -p "$CACHE_DIR" "$ANDROID_USER_DIR"

# Persist the auto-generated debug keystore (~/.android/debug.keystore) across
# container runs so that successive debug APKs share the same signature and
# can be installed with `adb install -r` without uninstalling first.
docker run --rm \
  -v "$SCRIPT_DIR:/workspace" \
  -v "$CACHE_DIR:/home/builder/.gradle" \
  -v "$ANDROID_USER_DIR:/home/builder/.android" \
  -w /workspace \
  "$IMAGE_NAME" \
  bash -c "gradle wrapper --gradle-version 8.9 --no-daemon >/dev/null 2>&1 || true; gradle $GRADLE_TASK --no-daemon --stacktrace"

echo ""
echo "APK output:"
find "$SCRIPT_DIR/app/build/outputs/apk/$FLAVOR/$BUILD_TYPE" -name "*.apk" 2>/dev/null \
  || echo "  (no APK found)"
