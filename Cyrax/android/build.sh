#!/bin/bash
# Android build script (wrapper for gradle)

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Check if gradle is installed
if ! command -v gradle &> /dev/null; then
    echo "Installing gradle..."
    # Fallback: use system gradle or Docker
    if command -v docker &> /dev/null; then
        echo "Using Docker gradle image"
        docker run --rm -v "$PROJECT_DIR":/workspace docker.io/library/gradle:latest gradle "$@"
        exit $?
    else
        echo "ERROR: gradle not found and Docker not available"
        echo "Install gradle or Docker to continue"
        exit 1
    fi
fi

# Use system gradle
gradle "$@"
