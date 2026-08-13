#!/usr/bin/env bash
set -e

WORKSPACE_ROOT="/Users/iml1s/Documents/mine/aosp-linux"
BUILD_DIR="${WORKSPACE_ROOT}/build_out"

echo "=== M1 Architecture Build & Verification Suite ==="
echo "Workspace Root: ${WORKSPACE_ROOT}"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}/bin" "${BUILD_DIR}/classes"

echo "--------------------------------------------------"
echo "[1/4] Checking AIDL & Structural Compliance..."
required_files=(
    "frameworks/base/core/java/android/system/linux/LinuxManager.java"
    "frameworks/base/core/java/android/system/linux/LinuxAppInfo.java"
    "frameworks/base/core/java/android/system/linux/ILinuxManager.aidl"
    "frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.aidl"
    "frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.aidl"
    "frameworks/base/core/java/android/system/linux/LinuxAppInfo.aidl"
    "frameworks/base/core/java/android/system/linux/ILinuxBridgeDaemon.aidl"
    "frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java"
    "frameworks/base/services/core/java/com/android/server/linux/LinuxManagerInternal.java"
    "frameworks/base/services/core/java/com/android/server/SystemService.java"
    "packages/apps/LinuxTerminal/AndroidManifest.xml"
    "system/sepolicy/private/linux_manager.te"
    "system/sepolicy/private/linux_bridge.te"
    "system/sepolicy/private/file_contexts"
    "system/linux_bridge/socket_server.h"
    "system/linux_bridge/socket_server.cpp"
    "system/linux_bridge/vsock_framing.h"
    "system/linux_bridge/vsock_framing.cpp"
    "system/linux_bridge/main.cpp"
    "system/linux_bridge/Android.bp"
    "Android.bp"
)

for file in "${required_files[@]}"; do
    if [ ! -f "${WORKSPACE_ROOT}/${file}" ]; then
        echo "ERROR: Required file missing: ${file}"
        exit 1
    fi
done
echo "PASS: All ${#required_files[@]} required M1 files present."

echo "--------------------------------------------------"
echo "[2/4] Compiling Java Framework & Service Modules..."
find "${WORKSPACE_ROOT}/frameworks/base/core/java" "${WORKSPACE_ROOT}/frameworks/base/services/core/java" "${WORKSPACE_ROOT}/tests/unit/stubs" -name "*.java" > "${BUILD_DIR}/sources.txt"
echo "${WORKSPACE_ROOT}/tests/unit/LinuxManagerServiceTest.java" >> "${BUILD_DIR}/sources.txt"
echo "${WORKSPACE_ROOT}/tests/unit/LinuxManagerStressTest.java" >> "${BUILD_DIR}/sources.txt"
echo "${WORKSPACE_ROOT}/tests/unit/ChallengerM1StressTest.java" >> "${BUILD_DIR}/sources.txt"

javac -cp /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar -d "${BUILD_DIR}/classes" @"${BUILD_DIR}/sources.txt"
echo "PASS: Java framework & service modules compiled cleanly."

echo "--------------------------------------------------"
echo "[3/4] Running Java Service & State Machine Unit Tests & Stress Suite..."
java -cp "${BUILD_DIR}/classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar" tests.unit.LinuxManagerServiceTest
java -cp "${BUILD_DIR}/classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar" tests.unit.LinuxManagerStressTest
java -cp "${BUILD_DIR}/classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar" tests.unit.ChallengerM1StressTest
echo "PASS: Java test suite & stress tests executed successfully."

echo "--------------------------------------------------"
echo "[4/4] Compiling and Running Native linux_bridge Daemon Tests..."
clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/socket_server.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_framing.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/hmac_auth.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_server.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/linux_bridge_test.cpp" \
    -o "${BUILD_DIR}/bin/linux_bridge_test"

"${BUILD_DIR}/bin/linux_bridge_test"
echo "PASS: Native bridge daemon test suite executed successfully."

echo "=================================================="
echo "M1 VERIFICATION COMPLETE: ALL 8/8 REQUIREMENTS PASSED"
