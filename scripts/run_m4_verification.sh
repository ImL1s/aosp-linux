#!/usr/bin/env bash
set -e

WORKSPACE_ROOT="/Users/iml1s/Documents/mine/aosp-linux"
BUILD_DIR="${WORKSPACE_ROOT}/build_out"

echo "=== M4 Wayland GUI & Recents Overview Build & Verification Suite ==="
echo "Workspace Root: ${WORKSPACE_ROOT}"
mkdir -p "${BUILD_DIR}/bin" "${BUILD_DIR}/classes"

echo "--------------------------------------------------"
echo "[1/4] Checking M4 File Structure & Component Compliance..."
required_files=(
    "frameworks/base/services/core/java/com/android/server/linux/LinuxWindowBridgeService.java"
    "system/linux_bridge/wayland_buffer_sharing.h"
    "system/linux_bridge/wayland_buffer_sharing.cpp"
    "packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java"
    "packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/window/WindowResizePacer.java"
    "packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java"
    "guest/portal-agent/Cargo.toml"
    "guest/portal-agent/src/main.rs"
    "guest/portal-agent/src/desktop_parser.rs"
    "guest/portal-agent/src/inotify_watcher.rs"
    "tests/unit/VirtioGpuDmabufTest.cpp"
    "tests/unit/LinuxWindowBridgeServiceTest.java"
    "tests/unit/LinuxAppProxyActivityTest.java"
    "tests/unit/LinuxAppTrackerTest.java"
)

for file in "${required_files[@]}"; do
    if [ ! -f "${WORKSPACE_ROOT}/${file}" ]; then
        echo "ERROR: Required M4 file missing: ${file}"
        exit 1
    fi
done
echo "PASS: All ${#required_files[@]} required M4 files present."

echo "--------------------------------------------------"
echo "[2/4] Compiling & Executing C++ Native dma-buf Sharing Unit Tests..."
clang++ -std=c++17 -Wall -Wextra -I"${WORKSPACE_ROOT}/system/linux_bridge" \
    "${WORKSPACE_ROOT}/system/linux_bridge/wayland_buffer_sharing.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/VirtioGpuDmabufTest.cpp" \
    -o "${BUILD_DIR}/bin/VirtioGpuDmabufTest"

"${BUILD_DIR}/bin/VirtioGpuDmabufTest"
echo "PASS: Native C++ virtio-gpu dma-buf test suite executed successfully."

echo "--------------------------------------------------"
echo "[3/4] Compiling & Executing Java Framework & App Unit Tests..."
javac -d "${BUILD_DIR}/classes" \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/annotation/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/net/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/system/linux/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/content/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/content/res/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/app/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/os/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/util/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/view/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/view/inputmethod/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/hardware/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/android/graphics/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/core/java/org/json/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/services/core/java/com/android/server/*.java \
    "${WORKSPACE_ROOT}"/frameworks/base/services/core/java/com/android/server/linux/*.java \
    "${WORKSPACE_ROOT}"/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/window/WindowResizePacer.java \
    "${WORKSPACE_ROOT}"/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/LinuxAppProxyActivity.java \
    "${WORKSPACE_ROOT}"/packages/apps/Launcher3/src/com/android/launcher3/linux/LinuxAppTracker.java \
    "${WORKSPACE_ROOT}"/tests/unit/LinuxWindowBridgeServiceTest.java \
    "${WORKSPACE_ROOT}"/tests/unit/LinuxAppProxyActivityTest.java \
    "${WORKSPACE_ROOT}"/tests/unit/LinuxAppTrackerTest.java

java -cp "${BUILD_DIR}/classes" tests.unit.LinuxWindowBridgeServiceTest
java -cp "${BUILD_DIR}/classes" tests.unit.LinuxAppProxyActivityTest
java -cp "${BUILD_DIR}/classes" tests.unit.LinuxAppTrackerTest
echo "PASS: Java unit tests executed successfully."

echo "--------------------------------------------------"
echo "[4/4] Running M4 Feature Coverage E2E Tests..."
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --filter R4

echo "=================================================="
echo "M4 VERIFICATION COMPLETE: ALL 6/6 FEATURES PASSED"
