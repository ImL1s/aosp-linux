#!/usr/bin/env bash
set -e

WORKSPACE_ROOT="/Users/iml1s/Documents/mine/aosp-linux"
BUILD_DIR="${WORKSPACE_ROOT}/build_out"

echo "=== M2 Architecture Build & Verification Suite ==="
echo "Workspace Root: ${WORKSPACE_ROOT}"
mkdir -p "${BUILD_DIR}/bin" "${BUILD_DIR}/classes"

echo "--------------------------------------------------"
echo "[1/6] Checking Structural & File Compliance..."
required_files=(
    "guest/config/vm_config.json"
    "guest/scripts/launch_vm.sh"
    "guest/scripts/init_storage_layout.sh"
    "guest/scripts/guest_mount_overlay.sh"
    "guest/systemd/android-bridge-agent.service"
    "guest/bridge-agent/Cargo.toml"
    "guest/bridge-agent/src/main.rs"
    "guest/bridge-agent/src/auth.rs"
    "guest/bridge-agent/src/vsock.rs"
    "system/linux_bridge/vsock_framing.h"
    "system/linux_bridge/vsock_framing.cpp"
    "system/linux_bridge/hmac_auth.h"
    "system/linux_bridge/hmac_auth.cpp"
    "system/linux_bridge/vsock_server.h"
    "system/linux_bridge/vsock_server.cpp"
    "system/linux_bridge/socket_server.h"
    "system/linux_bridge/socket_server.cpp"
    "frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java"
    "frameworks/base/services/core/java/com/android/server/linux/LinuxCeKeyManager.java"
    "tests/e2e/tier1_feature_coverage/test_m2_tier1.py"
    "tests/e2e/tier2_boundary_corner/test_m2_tier2.py"
)

for file in "${required_files[@]}"; do
    if [ ! -f "${WORKSPACE_ROOT}/${file}" ]; then
        echo "ERROR: Required file missing: ${file}"
        exit 1
    fi
done
echo "PASS: All ${#required_files[@]} required M2 files present."

echo "--------------------------------------------------"
echo "[2/6] Compiling Java Service & Key Manager..."
find "${WORKSPACE_ROOT}/frameworks/base/core/java" "${WORKSPACE_ROOT}/frameworks/base/services/core/java" -name "*.java" > "${BUILD_DIR}/sources.txt"
echo "${WORKSPACE_ROOT}/tests/unit/LinuxManagerServiceTest.java" >> "${BUILD_DIR}/sources.txt"
javac -d "${BUILD_DIR}/classes" @"${BUILD_DIR}/sources.txt"
java -cp "${BUILD_DIR}/classes" tests.unit.LinuxManagerServiceTest > /dev/null
echo "PASS: Java framework & service modules compiled & verified."

echo "--------------------------------------------------"
echo "[3/6] Compiling and Running Native C++ Daemon Tests..."
clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/hmac_auth.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_framing.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/socket_server.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_server.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/linux_bridge_test.cpp" \
    -o "${BUILD_DIR}/bin/linux_bridge_test"

clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/hmac_auth.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_framing.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/challenger_m2_framing_test.cpp" \
    -o "${BUILD_DIR}/bin/challenger_m2_framing_test"

clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/hmac_auth.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_framing.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/challenger_m2_hmac_test.cpp" \
    -o "${BUILD_DIR}/bin/challenger_m2_hmac_test"

clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/hmac_auth.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_framing.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/socket_server.cpp" \
    "${WORKSPACE_ROOT}/system/linux_bridge/vsock_server.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/challenger_m2_empirical_test.cpp" \
    -o "${BUILD_DIR}/bin/challenger_m2_empirical_test"

"${BUILD_DIR}/bin/linux_bridge_test" > /dev/null
"${BUILD_DIR}/bin/challenger_m2_framing_test" > /dev/null
"${BUILD_DIR}/bin/challenger_m2_hmac_test" > /dev/null
"${BUILD_DIR}/bin/challenger_m2_empirical_test" > /dev/null
echo "PASS: All C++ native test suites executed successfully."

echo "--------------------------------------------------"
echo "[4/6] Compiling and Testing Rust Guest Agent..."
(cd "${WORKSPACE_ROOT}/guest/bridge-agent" && ~/.cargo/bin/cargo check > /dev/null 2>&1 && ~/.cargo/bin/cargo test > /dev/null 2>&1)
echo "PASS: Rust Guest Agent (android-bridge-agent) compiled & verified."

echo "--------------------------------------------------"
echo "[5/6] Verifying Shell Script Syntax..."
bash -n "${WORKSPACE_ROOT}/guest/scripts/launch_vm.sh"
bash -n "${WORKSPACE_ROOT}/guest/scripts/init_storage_layout.sh"
bash -n "${WORKSPACE_ROOT}/guest/scripts/guest_mount_overlay.sh"
echo "PASS: All Guest & Host shell scripts passed syntax checks."

echo "--------------------------------------------------"
echo "[6/6] Running Python E2E Test Suites for Milestone M2..."
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R2-001 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R2-002 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R2-003 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R2-004 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R2-005 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R2-001 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R2-002 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R2-003 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R2-004 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R2-005 > /dev/null
echo "PASS: All E2E Tier 1 & Tier 2 test suites passed cleanly."

echo "=================================================="
echo "M2 VERIFICATION COMPLETE: ALL 6/6 STAGES PASSED SUCCESSFULLY"
