#!/usr/bin/env bash
# Shell launcher script for AOSP Dual-OS E2E Test Runner CLI.
# Invokes python3 /Users/iml1s/Documents/mine/aosp-linux/tests/e2e/runner.py "$@"

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_EXEC="$(which python3 || echo "python3")"
RUNNER_SCRIPT="$SCRIPT_DIR/runner.py"

echo "================================================================================"
echo "Starting AOSP Dual-OS E2E Test Suite Execution..."
echo "================================================================================"

exec "$PYTHON_EXEC" "$RUNNER_SCRIPT" "$@"
