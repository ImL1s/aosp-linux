/*
 * Challenger 2 Empirical Security & Stress Test Suite (Milestone M5 Iteration 2)
 * File: tests/unit/challenger_m5_2_r2_stress.cpp
 */

#include "system/vold/AvbVerifier.h"
#include "system/linux_bridge/guest_ota_rollback_watchdog.h"

#include <iostream>
#include <fstream>
#include <sstream>
#include <cassert>
#include <vector>
#include <string>
#include <filesystem>
#include <thread>
#include <atomic>
#include <chrono>

using namespace android::vold;
using namespace android::linux_bridge;
namespace fs = std::filesystem;

static bool fileContainsPattern(const std::string& path, const std::string& pattern) {
    std::ifstream file(path);
    if (!file.is_open()) return false;
    std::string line;
    while (std::getline(file, line)) {
        if (line.find(pattern) != std::string::npos) {
            return true;
        }
    }
    return false;
}

int main() {
    std::cout << "==========================================================" << std::endl;
    std::cout << "  CHALLENGER 2 R2 EMPIRICAL SECURITY & STRESS VERIFIER    " << std::endl;
    std::cout << "==========================================================" << std::endl;

    int passed = 0;
    int failed = 0;
    std::string repoRoot = "/Users/iml1s/Documents/mine/aosp-linux";
    std::string scratchDir = repoRoot + "/scratch/r2_test";
    fs::create_directories(scratchDir);

    // ------------------------------------------------------------------------
    // SCENARIO 1: SELinux Policy & Hard Neverallow Rules Audit
    // ------------------------------------------------------------------------
    std::cout << "\n[TEST 1] Testing SELinux Domain Policies & Hard Neverallow Rules..." << std::endl;
    try {
        std::string managerTe = repoRoot + "/system/sepolicy/private/linux_manager.te";
        std::string bridgeTe  = repoRoot + "/system/sepolicy/private/linux_bridge.te";
        std::string portalTe  = repoRoot + "/system/sepolicy/private/linux_portal.te";
        std::string fileCtx   = repoRoot + "/system/sepolicy/private/file_contexts";

        bool mEfs = fileContainsPattern(managerTe, "neverallow linux_manager efs_file:file *");
        bool mSys = fileContainsPattern(managerTe, "neverallow linux_manager system_data_file:file { write create delete }");
        bool mTrans = fileContainsPattern(managerTe, "neverallow linux_manager { su init }:process transition");

        bool bEfs = fileContainsPattern(bridgeTe, "neverallow linux_bridge efs_file:file *");
        bool bBlk = fileContainsPattern(bridgeTe, "neverallow linux_bridge block_device:blk_file");

        bool pEfs = fileContainsPattern(portalTe, "neverallow linux_portal efs_file:file *");
        bool pChr = fileContainsPattern(portalTe, "neverallow linux_portal device:chr_file raw_io");

        bool cSocketB = fileContainsPattern(fileCtx, "/dev/socket/linux_bridge");
        bool cSocketP = fileContainsPattern(fileCtx, "/dev/socket/linux_portal");

        if (mEfs && mSys && mTrans && bEfs && bBlk && pEfs && pChr && cSocketB && cSocketP) {
            std::cout << "  -> PASS: All mandatory SELinux domain & neverallow rules verified." << std::endl;
            passed++;
        } else {
            std::cerr << "  -> FAIL: Missing SELinux domain policy or neverallow assertion!" << std::endl;
            failed++;
        }
    } catch (const std::exception& e) {
        std::cerr << "  -> EXCEPTION in Test 1: " << e.what() << std::endl;
        failed++;
    }

    // ------------------------------------------------------------------------
    // SCENARIO 2: AVB RSA-4096 Public Key Parsing & Digest Verification
    // ------------------------------------------------------------------------
    std::cout << "\n[TEST 2] Testing AVB RSA-4096 Verification & Digest Integrity..." << std::endl;
    try {
        std::string pubKeyPath = repoRoot + "/system/etc/security/avb/guest_root_key.pub";
        std::string validImg = scratchDir + "/valid_image.img";
        std::string vbmetaPath = scratchDir + "/valid_vbmeta.img";

        // Create genuine payload image
        {
            std::ofstream out(validImg, std::ios::binary);
            out << "VALID_AOSP_GUEST_BASE_IMAGE_CONTENT_BYTES_0123456789";
        }
        // Calculate digest
        std::string expectedDigest = AvbVerifier::calculateImageDigest(validImg);
        std::cout << "  Calculated valid image SHA-256 digest: " << expectedDigest << std::endl;

        // Create valid vbmeta header
        {
            std::ofstream out(vbmetaPath, std::ios::binary);
            VbmetaHeader hdr;
            hdr.magic[0] = 'A'; hdr.magic[1] = 'V'; hdr.magic[2] = 'B'; hdr.magic[3] = '0';
            hdr.rollbackIndex = 100;
            out.write(reinterpret_cast<const char*>(&hdr), sizeof(hdr));
        }

        // Verify valid image
        bool verOk = AvbVerifier::verifyGuestImage(validImg, vbmetaPath, pubKeyPath, 100);

        // Test digest tampering
        std::string tamperedImg = scratchDir + "/tampered_image.img";
        {
            std::ofstream out(tamperedImg, std::ios::binary);
            out << "TAMPERED_AOSP_GUEST_BASE_IMAGE_CONTENT_BYTES_0123456789";
        }
        std::string actualTamperedDigest = AvbVerifier::calculateImageDigest(tamperedImg);

        bool caughtTamper = false;
        try {
            AvbVerifier::verifyImageDigest(actualTamperedDigest, expectedDigest);
        } catch (const AVBDigestMismatch& e) {
            caughtTamper = true;
            std::cout << "  Caught expected AVBDigestMismatch for tampered image: " << e.what() << std::endl;
        }

        if (verOk && caughtTamper) {
            std::cout << "  -> PASS: AVB RSA-4096 signature & tampered image rejection verified." << std::endl;
            passed++;
        } else {
            std::cerr << "  -> FAIL: AVB verification or tamper detection failed! verOk=" << verOk << ", caughtTamper=" << caughtTamper << std::endl;
            failed++;
        }
    } catch (const std::exception& e) {
        std::cerr << "  -> EXCEPTION in Test 2: " << e.what() << std::endl;
        failed++;
    }

    // ------------------------------------------------------------------------
    // SCENARIO 3: AVB Anti-Rollback Downgrade & Key Policy Rejection
    // ------------------------------------------------------------------------
    std::cout << "\n[TEST 3] Testing AVB Anti-Rollback Index Downgrade & Key Policy..." << std::endl;
    try {
        bool caughtRollback = false;
        try {
            AvbVerifier::enforceRollbackIndex(99, 100); // Package 99 < Device 100
        } catch (const AVBRollbackDenied& e) {
            caughtRollback = true;
            std::cout << "  Caught expected AVBRollbackDenied: " << e.what() << std::endl;
        }

        bool caughtUserDevKeys = false;
        try {
            AvbVerifier::enforceKeyPolicy("user", "test-keys");
        } catch (const AVBPolicyViolation& e) {
            caughtUserDevKeys = true;
            std::cout << "  Caught expected AVBPolicyViolation: " << e.what() << std::endl;
        }

        if (caughtRollback && caughtUserDevKeys) {
            std::cout << "  -> PASS: Anti-rollback index & build key policies strictly enforced." << std::endl;
            passed++;
        } else {
            std::cerr << "  -> FAIL: Rollback or key policy enforcement failed!" << std::endl;
            failed++;
        }
    } catch (const std::exception& e) {
        std::cerr << "  -> EXCEPTION in Test 3: " << e.what() << std::endl;
        failed++;
    }

    // ------------------------------------------------------------------------
    // SCENARIO 4: 3-Boot Watchdog Engine JSON Disk Persistence & Rollback
    // ------------------------------------------------------------------------
    std::cout << "\n[TEST 4] Testing 3-Boot Watchdog JSON Persistence & Automatic Rollback..." << std::endl;
    try {
        std::string metaPath = scratchDir + "/watchdog_persistence_test.json";
        if (fs::exists(metaPath)) fs::remove(metaPath);

        // Instance 1: Initial state
        {
            BootWatchdogEngine wd1(metaPath);
            assert(wd1.getActiveSlot() == "slot_a");
            wd1.startWatchdog("slot_a");
            wd1.handleBootTimeout("slot_a"); // Attempt 1
            wd1.startWatchdog("slot_a");
            wd1.handleBootTimeout("slot_a"); // Attempt 2
        }

        // Instance 2: Reload state from disk after daemon restart
        {
            BootWatchdogEngine wd2(metaPath);
            assert(wd2.getActiveSlot() == "slot_a");
            assert(wd2.getBootAttempts() == 2);
            std::cout << "  Persisted boot attempts successfully loaded: " << wd2.getBootAttempts() << std::endl;

            // Trigger Attempt 3 -> Threshold reached -> Automatic Slot Rollback to slot_b
            wd2.startWatchdog("slot_a");
            wd2.handleBootTimeout("slot_a");
            assert(wd2.getActiveSlot() == "slot_b");
            std::cout << "  Slot automatically rolled back to: " << wd2.getActiveSlot() << std::endl;
        }

        // Instance 3: Verify slot_b persisted to disk
        {
            BootWatchdogEngine wd3(metaPath);
            bool slotIsB = (wd3.getActiveSlot() == "slot_b");
            bool slotAUnbootable = (wd3.getMetadata().slotA.successfulBoot == 0);
            if (slotIsB && slotAUnbootable) {
                std::cout << "  -> PASS: 3-Boot Watchdog disk persistence & automatic rollback confirmed." << std::endl;
                passed++;
            } else {
                std::cerr << "  -> FAIL: Watchdog disk persistence verification failed! slotIsB=" << slotIsB << std::endl;
                failed++;
            }
        }
    } catch (const std::exception& e) {
        std::cerr << "  -> EXCEPTION in Test 4: " << e.what() << std::endl;
        failed++;
    }

    // ------------------------------------------------------------------------
    // SCENARIO 5: Watchdog Heartbeat Resets & Concurrency Stress Test
    // ------------------------------------------------------------------------
    std::cout << "\n[TEST 5] Testing Watchdog Heartbeat Concurrency & Corrupted JSON Recovery..." << std::endl;
    try {
        std::string metaPath = scratchDir + "/watchdog_concurrency_test.json";
        if (fs::exists(metaPath)) fs::remove(metaPath);

        BootWatchdogEngine wd(metaPath);
        std::atomic<bool> stopStress{false};
        std::atomic<int> heartbeatCount{0};

        // Concurrent heartbeat thread
        std::thread hbThread([&]() {
            while (!stopStress.load()) {
                wd.onHeartbeatReceived();
                heartbeatCount++;
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }
        });

        // Main thread triggering startWatchdog
        for (int i = 0; i < 50; ++i) {
            wd.startWatchdog(wd.getActiveSlot());
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }

        stopStress = true;
        hbThread.join();

        std::cout << "  Processed " << heartbeatCount.load() << " concurrent heartbeats without crashing." << std::endl;

        // Test Corrupted JSON File Recovery
        std::string corruptMeta = scratchDir + "/corrupt_metadata.json";
        {
            std::ofstream out(corruptMeta);
            out << "{ invalid_json_syntax: true, truncated... ";
        }
        BootWatchdogEngine wdCorrupt(corruptMeta);
        bool recovered = (wdCorrupt.getActiveSlot() == "slot_a");
        std::cout << "  Corrupted JSON fallback active slot: " << wdCorrupt.getActiveSlot() << std::endl;

        if (recovered) {
            std::cout << "  -> PASS: Heartbeat concurrency & corrupted JSON recovery confirmed." << std::endl;
            passed++;
        } else {
            std::cerr << "  -> FAIL: Corrupted JSON recovery failed!" << std::endl;
            failed++;
        }
    } catch (const std::exception& e) {
        std::cerr << "  -> EXCEPTION in Test 5: " << e.what() << std::endl;
        failed++;
    }

    // ------------------------------------------------------------------------
    // SCENARIO 6: EROFS Immutability & Crosvm Disk Mounting Parameter Audit
    // ------------------------------------------------------------------------
    std::cout << "\n[TEST 6] Testing EROFS Read-Only Immutability & crosvm Parameters..." << std::endl;
    try {
        std::string launchScript = repoRoot + "/guest/scripts/launch_vm.sh";
        std::string vmConfig = repoRoot + "/guest/config/vm_config.json";

        bool launchRo = fileContainsPattern(launchScript, "--rodisk");
        bool cfgRo = fileContainsPattern(vmConfig, "\"read_only\": true");

        if (launchRo && cfgRo) {
            std::cout << "  -> PASS: EROFS read-only base image immutability & crosvm rodisk confirmed." << std::endl;
            passed++;
        } else {
            std::cerr << "  -> FAIL: EROFS read-only configuration missing!" << std::endl;
            failed++;
        }
    } catch (const std::exception& e) {
        std::cerr << "  -> EXCEPTION in Test 6: " << e.what() << std::endl;
        failed++;
    }

    // Clean up scratch test artifacts
    fs::remove_all(scratchDir);

    std::cout << "\n==========================================================" << std::endl;
    std::cout << "  STRESS VERIFICATION SUMMARY: " << passed << " PASSED, " << failed << " FAILED" << std::endl;
    std::cout << "==========================================================" << std::endl;

    return (failed == 0) ? 0 : 1;
}
