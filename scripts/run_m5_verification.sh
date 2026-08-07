#!/usr/bin/env bash
set -e

WORKSPACE_ROOT="/Users/iml1s/Documents/mine/aosp-linux"
BUILD_DIR="${WORKSPACE_ROOT}/build_out"

echo "=== M5 Hardware Portals, Virtiofs, SELinux & OTA Verification Suite ==="
echo "Workspace Root: ${WORKSPACE_ROOT}"
mkdir -p "${BUILD_DIR}/bin" "${BUILD_DIR}/classes"

echo "--------------------------------------------------"
echo "[1/6] Checking Structural & File Compliance..."
required_files=(
    "frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java"
    "frameworks/base/services/core/java/com/android/server/linux/LinuxAudioPolicyHandler.java"
    "frameworks/base/services/core/java/com/android/server/linux/LinuxPermissionActivity.java"
    "frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java"
    "system/sepolicy/private/linux_portal.te"
    "system/sepolicy/private/linux_manager.te"
    "system/sepolicy/private/linux_bridge.te"
    "system/sepolicy/private/file_contexts"
    "system/linux_bridge/guest_ota_rollback_watchdog.h"
    "system/linux_bridge/guest_ota_rollback_watchdog.cpp"
    "system/vold/AvbVerifier.h"
    "system/vold/AvbVerifier.cpp"
    "system/etc/security/avb/guest_root_key.pub"
    "guest/bridge-agent/src/ota_rollback.rs"
    "tests/unit/LinuxPortalServiceTest.java"
    "tests/unit/LinuxAudioPolicyTest.java"
    "tests/unit/LinuxStorageProviderTest.java"
    "tests/unit/guest_ota_rollback_watchdog_test.cpp"
    "tests/unit/avb_verifier_test.cpp"
    "tests/e2e/tier1_feature_coverage/test_m5_tier1.py"
    "tests/e2e/tier2_boundary_corner/test_m5_tier2.py"
)

for file in "${required_files[@]}"; do
    if [ ! -f "${WORKSPACE_ROOT}/${file}" ]; then
        echo "ERROR: Required M5 file missing: ${file}"
        exit 1
    fi
done
echo "PASS: All ${#required_files[@]} required M5 files present."

echo "--------------------------------------------------"
echo "[2/6] Compiling Java Framework & Service Modules..."
find "${WORKSPACE_ROOT}/frameworks/base/core/java" "${WORKSPACE_ROOT}/frameworks/base/services/core/java" -name "*.java" > "${BUILD_DIR}/m5_sources.txt"
echo "${WORKSPACE_ROOT}/tests/unit/LinuxPortalServiceTest.java" >> "${BUILD_DIR}/m5_sources.txt"
echo "${WORKSPACE_ROOT}/tests/unit/LinuxAudioPolicyTest.java" >> "${BUILD_DIR}/m5_sources.txt"
echo "${WORKSPACE_ROOT}/tests/unit/LinuxStorageProviderTest.java" >> "${BUILD_DIR}/m5_sources.txt"
echo "${WORKSPACE_ROOT}/tests/unit/LinuxManagerServiceTest.java" >> "${BUILD_DIR}/m5_sources.txt"
javac -d "${BUILD_DIR}/classes" @"${BUILD_DIR}/m5_sources.txt"
echo "PASS: Java framework & service modules compiled cleanly."

echo "--------------------------------------------------"
echo "[3/6] Running Java Unit Test Suite..."
java -cp "${BUILD_DIR}/classes" tests.unit.LinuxPortalServiceTest
java -cp "${BUILD_DIR}/classes" tests.unit.LinuxAudioPolicyTest
java -cp "${BUILD_DIR}/classes" tests.unit.LinuxStorageProviderTest
echo "PASS: Java M5 unit tests executed successfully."

echo "--------------------------------------------------"
echo "[4/6] Compiling and Running C++ Watchdog & AVB Tests..."
OPENSSL_CFLAGS=$(pkg-config --cflags openssl 2>/dev/null || echo "-I/opt/homebrew/opt/openssl@3/include")
OPENSSL_LIBS=$(pkg-config --libs openssl 2>/dev/null || echo "-L/opt/homebrew/opt/openssl@3/lib -lcrypto")

clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" \
    "${WORKSPACE_ROOT}/system/linux_bridge/guest_ota_rollback_watchdog.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/guest_ota_rollback_watchdog_test.cpp" \
    -o "${BUILD_DIR}/bin/guest_ota_rollback_watchdog_test"

clang++ -std=c++20 -Wall -Wextra -pthread -I"${WORKSPACE_ROOT}" ${OPENSSL_CFLAGS} \
    "${WORKSPACE_ROOT}/system/vold/AvbVerifier.cpp" \
    "${WORKSPACE_ROOT}/tests/unit/avb_verifier_test.cpp" \
    ${OPENSSL_LIBS} \
    -o "${BUILD_DIR}/bin/avb_verifier_test"

"${BUILD_DIR}/bin/guest_ota_rollback_watchdog_test"
"${BUILD_DIR}/bin/avb_verifier_test"
echo "PASS: All C++ native test suites executed successfully."


echo "--------------------------------------------------"
echo "[5/6] Compiling Rust Guest Agent (android-bridge-agent)..."
(cd "${WORKSPACE_ROOT}/guest/bridge-agent" && ~/.cargo/bin/cargo check > /dev/null 2>&1)
echo "PASS: Rust Guest Agent compiled & verified."

echo "--------------------------------------------------"
echo "[6/6] Running Python E2E Test Suite for Milestone M5 Features F-R5-001..014..."
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-001 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-002 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-003 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-004 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-005 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-006 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-007 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-008 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-009 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-010 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-011 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-012 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-013 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 1 --feature F-R5-014 > /dev/null
echo "PASS: E2E Tier 1 tests passed cleanly."

python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-001 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-002 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-003 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-004 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-005 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-006 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-007 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-008 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-009 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-010 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-011 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-012 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-013 > /dev/null
python3 "${WORKSPACE_ROOT}/tests/e2e/runner.py" --tier 2 --feature F-R5-014 > /dev/null
echo "PASS: E2E Tier 2 tests passed cleanly."

echo "=================================================="
echo "M5 VERIFICATION COMPLETE: ALL 14/14 FEATURES PASSED SUCCESSFULLY"
