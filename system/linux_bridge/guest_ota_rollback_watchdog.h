/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <string>
#include <atomic>
#include <thread>
#include <condition_variable>
#include <mutex>

namespace android {
namespace linux_bridge {

struct SlotInfo {
    std::string imagePath;
    std::string version;
    std::string sha256;
    int successfulBoot = 1;
    uint64_t rollbackIndex = 1000;
};

struct SlotMetadata {
    std::string activeSlot = "slot_a";
    SlotInfo slotA;
    SlotInfo slotB;
    int bootAttempts = 0;
    int maxBootAttempts = 3;
};

class BootWatchdogEngine {
private:
    std::atomic<bool> mHeartbeatReceived{false};
    std::atomic<bool> mStopRequested{false};
    std::atomic<uint64_t> mWatchdogGen{0};
    std::thread mTimerThread;
    std::condition_variable mCv;
    std::mutex mCvMutex;
    int mMaxTimeoutSec = 60;
    std::string mMetadataPath = "/data/system/linux/slot_metadata.json";
    SlotMetadata mMetadata;

    void stopWatchdogThread();



public:
    BootWatchdogEngine();
    explicit BootWatchdogEngine(const std::string& metadataPath);
    ~BootWatchdogEngine();

    void loadMetadata();
    void saveMetadata();

    void startWatchdog(const std::string& currentSlot);
    void onHeartbeatReceived();
    void handleBootTimeout(const std::string& failedSlot);
    void performSlotRollback(const std::string& failedSlot, const std::string& targetSlot);
    void forceRollback();

    std::string getActiveSlot() const { return mMetadata.activeSlot; }
    int getBootAttempts() const { return mMetadata.bootAttempts; }
    SlotMetadata getMetadata() const { return mMetadata; }
};

} // namespace linux_bridge
} // namespace android
