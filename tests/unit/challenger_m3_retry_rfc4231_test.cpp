#include <iostream>
#include <vector>
#include <string>
#include <cassert>
#include <iomanip>
#include <sstream>
#include "system/linux_bridge/hmac_auth.h"

using namespace android::system::linux_bridge;

std::string toHex(const std::vector<uint8_t>& bytes) {
    std::ostringstream oss;
    for (uint8_t b : bytes) {
        oss << std::hex << std::setw(2) << std::setfill('0') << (int)b;
    }
    return oss.str();
}

int main() {
    std::cout << "=== Challenger M3 Retry: RFC 4231 Full Vector Verification (vs Python stdlib) ===" << std::endl;
    int passCount = 0;

    // Test Case 1: Key = 0x0b * 20, Data = "Hi There"
    {
        std::vector<uint8_t> key(20, 0x0b);
        std::string dataStr = "Hi There";
        std::vector<uint8_t> data(dataStr.begin(), dataStr.end());
        std::string expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7";
        std::string actual = toHex(HmacAuth::computeHmacSha256(key, data));
        if (actual == expected) {
            std::cout << "[PASS] RFC 4231 Test Case 1" << std::endl;
            passCount++;
        } else {
            std::cout << "[FAIL] RFC 4231 Test Case 1: Expected " << expected << " got " << actual << std::endl;
        }
    }

    // Test Case 2: Key = "Jefe", Data = "what do ya want for nothing?"
    {
        std::string keyStr = "Jefe";
        std::vector<uint8_t> key(keyStr.begin(), keyStr.end());
        std::string dataStr = "what do ya want for nothing?";
        std::vector<uint8_t> data(dataStr.begin(), dataStr.end());
        std::string expected = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";
        std::string actual = toHex(HmacAuth::computeHmacSha256(key, data));
        if (actual == expected) {
            std::cout << "[PASS] RFC 4231 Test Case 2" << std::endl;
            passCount++;
        } else {
            std::cout << "[FAIL] RFC 4231 Test Case 2: Expected " << expected << " got " << actual << std::endl;
        }
    }

    // Test Case 3: Key = 0xaa * 20, Data = 0xdd * 50
    {
        std::vector<uint8_t> key(20, 0xaa);
        std::vector<uint8_t> data(50, 0xdd);
        std::string expected = "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe";
        std::string actual = toHex(HmacAuth::computeHmacSha256(key, data));
        if (actual == expected) {
            std::cout << "[PASS] RFC 4231 Test Case 3" << std::endl;
            passCount++;
        } else {
            std::cout << "[FAIL] RFC 4231 Test Case 3: Expected " << expected << " got " << actual << std::endl;
        }
    }

    // Test Case 4: Key = 0x01..0x19 (25 bytes), Data = 0xcd * 50
    {
        std::vector<uint8_t> key;
        for (uint8_t i = 1; i <= 25; ++i) key.push_back(i);
        std::vector<uint8_t> data(50, 0xcd);
        std::string expected = "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b";
        std::string actual = toHex(HmacAuth::computeHmacSha256(key, data));
        if (actual == expected) {
            std::cout << "[PASS] RFC 4231 Test Case 4" << std::endl;
            passCount++;
        } else {
            std::cout << "[FAIL] RFC 4231 Test Case 4: Expected " << expected << " got " << actual << std::endl;
        }
    }

    // Test Case 6: Key = 0xaa * 131, Data = "Test Using Larger Than Block-Size Key - Hash Key First"
    {
        std::vector<uint8_t> key(131, 0xaa);
        std::string dataStr = "Test Using Larger Than Block-Size Key - Hash Key First";
        std::vector<uint8_t> data(dataStr.begin(), dataStr.end());
        std::string expected = "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54";
        std::string actual = toHex(HmacAuth::computeHmacSha256(key, data));
        if (actual == expected) {
            std::cout << "[PASS] RFC 4231 Test Case 6" << std::endl;
            passCount++;
        } else {
            std::cout << "[FAIL] RFC 4231 Test Case 6: Expected " << expected << " got " << actual << std::endl;
        }
    }

    // Test Case 7: Key = 0xaa * 131, Data = "This is a test using a larger than block-size key..."
    {
        std::vector<uint8_t> key(131, 0xaa);
        std::string dataStr = "This is a test using a larger than block-size key and a larger than block-size data. The key needs to be hashed before being used by the HMAC algorithm.";
        std::vector<uint8_t> data(dataStr.begin(), dataStr.end());
        std::string expected = "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2";
        std::string actual = toHex(HmacAuth::computeHmacSha256(key, data));
        if (actual == expected) {
            std::cout << "[PASS] RFC 4231 Test Case 7" << std::endl;
            passCount++;
        } else {
            std::cout << "[FAIL] RFC 4231 Test Case 7: Expected " << expected << " got " << actual << std::endl;
        }
    }

    std::cout << "Summary: " << passCount << " / 6 tests passed." << std::endl;
    return (passCount == 6) ? 0 : 1;
}
