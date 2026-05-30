#!/usr/bin/env bash
# Builds the Docker image used to compile the app. User/group IDs are passed so
# that build artifacts written into the mounted project are owned by the host
# user (not root).
set -euo pipefail

IMAGE_NAME="simple-video-player-builder:latest"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

docker build \
  --build-arg USER_ID="$(id -u)" \
  --build-arg GROUP_ID="$(id -g)" \
  -t "$IMAGE_NAME" \
  "$SCRIPT_DIR"

echo "Built image: $IMAGE_NAME"
