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

#include "guest_ota_rollback_watchdog.h"
#include <iostream>
#include <fstream>
#include <sstream>
#include <chrono>
#include <filesystem>

namespace android {
namespace linux_bridge {

BootWatchdogEngine::BootWatchdogEngine() {
    loadMetadata();
}

BootWatchdogEngine::BootWatchdogEngine(const std::string& metadataPath)
    : mMetadataPath(metadataPath) {
    loadMetadata();
}

void BootWatchdogEngine::stopWatchdogThread() {
    mStopRequested = true;
    mCv.notify_all();
    if (mTimerThread.joinable()) {
        mTimerThread.join();
    }
}

BootWatchdogEngine::~BootWatchdogEngine() {
    stopWatchdogThread();
}

void BootWatchdogEngine::loadMetadata() {
    std::ifstream f(mMetadataPath);
    if (!f.is_open()) {
        // Defaults
        mMetadata.activeSlot = "slot_a";
        mMetadata.slotA = {"/data/system/linux/base_a.img", "12.5.0-aosp1", "sha256_slot_a", 1, 1001};
        mMetadata.slotB = {"/data/system/linux/base_b.img", "12.4.0-aosp1", "sha256_slot_b", 1, 1000};
        mMetadata.bootAttempts = 0;
        mMetadata.maxBootAttempts = 3;
        saveMetadata();
        return;
    }

    std::stringstream buffer;
    buffer << f.rdbuf();
    std::string content = buffer.str();

    auto extractStr = [&](const std::string& key) -> std::string {
        size_t pos = content.find("\"" + key + "\"");
        if (pos == std::string::npos) return "";
        size_t colon = content.find(':', pos);
        if (colon == std::string::npos) return "";
        size_t quote1 = content.find('"', colon);
        if (quote1 == std::string::npos) return "";
        size_t quote2 = content.find('"', quote1 + 1);
        if (quote2 == std::string::npos) return "";
        return content.substr(quote1 + 1, quote2 - quote1 - 1);
    };

    auto extractInt = [&](const std::string& key, int defaultVal) -> int {
        size_t pos = content.find("\"" + key + "\"");
        if (pos == std::string::npos) return defaultVal;
        size_t colon = content.find(':', pos);
        if (colon == std::string::npos) return defaultVal;
        size_t start = content.find_first_of("0123456789-", colon);
        if (start == std::string::npos) return defaultVal;
        size_t end = content.find_first_not_of("0123456789-", start);
        return std::stoi(content.substr(start, end - start));
    };

    std::string act = extractStr("activeSlot");
    if (!act.empty()) mMetadata.activeSlot = act;
    mMetadata.bootAttempts = extractInt("bootAttempts", 0);
    mMetadata.maxBootAttempts = extractInt("maxBootAttempts", 3);

    auto extractIntInObj = [&](const std::string& objName, const std::string& key, int defaultVal) -> int {
        size_t objPos = content.find("\"" + objName + "\"");
        if (objPos == std::string::npos) return defaultVal;
        size_t pos = content.find("\"" + key + "\"", objPos);
        if (pos == std::string::npos) return defaultVal;
        size_t colon = content.find(':', pos);
        if (colon == std::string::npos) return defaultVal;
        size_t start = content.find_first_of("0123456789-", colon);
        if (start == std::string::npos) return defaultVal;
        size_t end = content.find_first_not_of("0123456789-", start);
        return std::stoi(content.substr(start, end - start));
    };

    mMetadata.slotA.successfulBoot = extractIntInObj("slotA", "successfulBoot", 1);
    mMetadata.slotB.successfulBoot = extractIntInObj("slotB", "successfulBoot", 1);
}

void BootWatchdogEngine::saveMetadata() {
    std::filesystem::path p(mMetadataPath);
    if (p.has_parent_path()) {
        std::filesystem::create_directories(p.parent_path());
    }
    std::ofstream f(mMetadataPath);
    if (!f.is_open()) return;
    f << "{\n";
    f << "  \"activeSlot\": \"" << mMetadata.activeSlot << "\",\n";
    f << "  \"bootAttempts\": " << mMetadata.bootAttempts << ",\n";
    f << "  \"maxBootAttempts\": " << mMetadata.maxBootAttempts << ",\n";
    f << "  \"slotA\": {\n";
    f << "    \"imagePath\": \"" << mMetadata.slotA.imagePath << "\",\n";
    f << "    \"version\": \"" << mMetadata.slotA.version << "\",\n";
    f << "    \"sha256\": \"" << mMetadata.slotA.sha256 << "\",\n";
    f << "    \"successfulBoot\": " << mMetadata.slotA.successfulBoot << ",\n";
    f << "    \"rollbackIndex\": " << mMetadata.slotA.rollbackIndex << "\n";
    f << "  },\n";
    f << "  \"slotB\": {\n";
    f << "    \"imagePath\": \"" << mMetadata.slotB.imagePath << "\",\n";
    f << "    \"version\": \"" << mMetadata.slotB.version << "\",\n";
    f << "    \"sha256\": \"" << mMetadata.slotB.sha256 << "\",\n";
    f << "    \"successfulBoot\": " << mMetadata.slotB.successfulBoot << ",\n";
    f << "    \"rollbackIndex\": " << mMetadata.slotB.rollbackIndex << "\n";
    f << "  }\n";
    f << "}\n";
}

void BootWatchdogEngine::startWatchdog(const std::string& currentSlot) {
    stopWatchdogThread();
    mStopRequested = false;
    mHeartbeatReceived = false;
    mMetadata.bootAttempts += 1;
    saveMetadata();

    uint64_t gen = ++mWatchdogGen;

    mTimerThread = std::thread([this, currentSlot, gen]() {
        auto start = std::chrono::steady_clock::now();
        while (!mStopRequested) {
            std::unique_lock<std::mutex> lock(mCvMutex);
            mCv.wait_for(lock, std::chrono::milliseconds(200), [this]() {
                return mStopRequested.load() || mHeartbeatReceived.load();
            });
            if (mStopRequested || gen != mWatchdogGen) return;
            if (mHeartbeatReceived) {
                mMetadata.bootAttempts = 0;
                if (currentSlot == "slot_a") mMetadata.slotA.successfulBoot = 1;
                else mMetadata.slotB.successfulBoot = 1;
                saveMetadata();
                std::cout << "[Watchdog] Heartbeat received from guest. Reset boot attempts to 0." << std::endl;
                return;
            }
            if (std::chrono::duration_cast<std::chrono::seconds>(
                    std::chrono::steady_clock::now() - start).count() >= mMaxTimeoutSec) {
                break;
            }
        }
        if (mStopRequested || gen != mWatchdogGen) return;
        std::cout << "[Watchdog] 60-second timeout reached! Triggering boot failure handling." << std::endl;
        handleBootTimeout(currentSlot);
    });
}


void BootWatchdogEngine::onHeartbeatReceived() {
    mHeartbeatReceived = true;
    mMetadata.bootAttempts = 0;
    mCv.notify_all();
}

void BootWatchdogEngine::handleBootTimeout(const std::string& failedSlot) {
    if (mMetadata.bootAttempts >= mMetadata.maxBootAttempts) {
        std::string targetSlot = (failedSlot == "slot_a") ? "slot_b" : "slot_a";
        std::cout << "[Watchdog] Boot attempt threshold (" << mMetadata.maxBootAttempts 
                  << ") exceeded on " << failedSlot << ". Triggering automatic slot rollback to " 
                  << targetSlot << std::endl;
        performSlotRollback(failedSlot, targetSlot);
    } else {
        std::cout << "[Watchdog] Boot attempt " << mMetadata.bootAttempts 
                  << " recorded on " << failedSlot << std::endl;
    }
}

void BootWatchdogEngine::performSlotRollback(const std::string& failedSlot, const std::string& targetSlot) {
    mMetadata.activeSlot = targetSlot;
    if (failedSlot == "slot_a") {
        mMetadata.slotA.successfulBoot = 0;
    } else {
        mMetadata.slotB.successfulBoot = 0;
    }
    mMetadata.bootAttempts = 0;
    saveMetadata();
    std::cout << "[Watchdog] Rollback complete. Active slot is now " << targetSlot 
              << ". User home volume (/home/user) preserved intact." << std::endl;
}

void BootWatchdogEngine::forceRollback() {
    std::string targetSlot = (mMetadata.activeSlot == "slot_a") ? "slot_b" : "slot_a";
    performSlotRollback(mMetadata.activeSlot, targetSlot);
}

} // namespace linux_bridge
} // namespace android

