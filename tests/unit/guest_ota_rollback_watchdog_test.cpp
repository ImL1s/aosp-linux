#include "system/linux_bridge/guest_ota_rollback_watchdog.h"
#include <iostream>
#include <cassert>
#include <filesystem>

using namespace android::linux_bridge;

int main() {
    std::cout << "=== Running Guest Ota Rollback Watchdog Test ===" << std::endl;

    std::string testMetadataPath = "/tmp/test_slot_metadata.json";
    if (std::filesystem::exists(testMetadataPath)) {
        std::filesystem::remove(testMetadataPath);
    }

    BootWatchdogEngine watchdog(testMetadataPath);

    std::string activeSlot = watchdog.getActiveSlot();
    assert(activeSlot == "slot_a");
    std::cout << "Initial Active Slot: " << activeSlot << std::endl;

    // Test heartbeat reset
    watchdog.startWatchdog("slot_a");
    watchdog.onHeartbeatReceived();
    assert(watchdog.getMetadata().bootAttempts == 0);
    std::cout << "Pass: Heartbeat reset bootAttempts to 0" << std::endl;

    // Simulate 3 consecutive boot attempt timeouts
    watchdog.startWatchdog("slot_a"); // attempt 1
    watchdog.handleBootTimeout("slot_a");
    assert(watchdog.getActiveSlot() == "slot_a");

    watchdog.startWatchdog("slot_a"); // attempt 2
    watchdog.handleBootTimeout("slot_a");
    assert(watchdog.getActiveSlot() == "slot_a");

    watchdog.startWatchdog("slot_a"); // attempt 3 -> exceeds threshold (3)
    watchdog.handleBootTimeout("slot_a");

    // Automatic rollback triggered by handleBootTimeout!
    assert(watchdog.getActiveSlot() == "slot_b");
    std::cout << "Active Slot after automatic rollback: " << watchdog.getActiveSlot() << std::endl;

    // Verify JSON persistence across daemon restarts
    BootWatchdogEngine restartedWatchdog(testMetadataPath);
    assert(restartedWatchdog.getActiveSlot() == "slot_b");
    std::cout << "Pass: State persisted to " << testMetadataPath << " across process restart." << std::endl;

    std::filesystem::remove(testMetadataPath);

    std::cout << "PASS: Guest Ota Rollback Watchdog Test Executed Successfully." << std::endl;
    return 0;
}

