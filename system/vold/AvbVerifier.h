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
#include <vector>
#include <cstdint>
#include <stdexcept>

namespace android {
namespace vold {

struct VbmetaHeader {
    char magic[4]; // "AVB0"
    uint32_t requiredLibavbVersionMajor;
    uint32_t requiredLibavbVersionMinor;
    uint64_t authenticationDataBlockSize;
    uint64_t auxiliaryDataBlockSize;
    uint32_t algorithmType;
    uint64_t rollbackIndex;
    uint32_t flags;
};

class AvbVerifier {
public:
    static bool verifyGuestImage(
            const std::string& imagePath,
            const std::string& vbmetaPath,
            const std::string& trustedPubKeyPath,
            uint64_t currentRollbackIndex);

    static std::string calculateImageDigest(const std::string& imagePath);

    static bool verifyImageDigest(const std::string& actualDigest, const std::string& expectedDigest);

    static void enforceRollbackIndex(uint64_t packageIndex, uint64_t deviceIndex);

    static void enforceKeyPolicy(const std::string& buildType, const std::string& keyType);
};


class AVBValidationError : public std::runtime_error {
public:
    explicit AVBValidationError(const std::string& msg) : std::runtime_error(msg) {}
};

class AVBDigestMismatch : public std::runtime_error {
public:
    explicit AVBDigestMismatch(const std::string& msg) : std::runtime_error(msg) {}
};

class AVBHeaderMissing : public std::runtime_error {
public:
    explicit AVBHeaderMissing(const std::string& msg) : std::runtime_error(msg) {}
};

class AVBRollbackDenied : public std::runtime_error {
public:
    explicit AVBRollbackDenied(const std::string& msg) : std::runtime_error(msg) {}
};

class AVBPolicyViolation : public std::runtime_error {
public:
    explicit AVBPolicyViolation(const std::string& msg) : std::runtime_error(msg) {}
};

} // namespace vold
} // namespace android
