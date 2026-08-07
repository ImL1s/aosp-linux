#include "system/vold/AvbVerifier.h"
#include <iostream>
#include <fstream>
#include <cassert>
#include <filesystem>

using namespace android::vold;

int main() {
    std::cout << "=== Running AVB Verifier Test ===" << std::endl;

    // Test rollback index enforcement
    try {
        AvbVerifier::enforceRollbackIndex(2, 3);
        assert(false && "Should have thrown AVBRollbackDenied");
    } catch (const AVBRollbackDenied& e) {
        std::cout << "Pass: Caught expected AVBRollbackDenied: " << e.what() << std::endl;
    }

    // Test digest mismatch
    try {
        AvbVerifier::verifyImageDigest("actual_digest_123", "expected_digest_456");
        assert(false && "Should have thrown AVBDigestMismatch");
    } catch (const AVBDigestMismatch& e) {
        std::cout << "Pass: Caught expected AVBDigestMismatch: " << e.what() << std::endl;
    }

    // Test key policy enforcement
    try {
        AvbVerifier::enforceKeyPolicy("user", "test-keys");
        assert(false && "Should have thrown AVBPolicyViolation");
    } catch (const AVBPolicyViolation& e) {
        std::cout << "Pass: Caught expected AVBPolicyViolation: " << e.what() << std::endl;
    }

    // Test calculateImageDigest on temporary image
    std::string testImgPath = "/tmp/test_avb_image.img";
    {
        std::ofstream out(testImgPath, std::ios::binary);
        out << "AVB_TEST_IMAGE_DATA_12345";
    }
    std::string digest = AvbVerifier::calculateImageDigest(testImgPath);
    assert(!digest.empty() && digest.length() == 64);
    std::cout << "Pass: SHA-256 digest calculated: " << digest << std::endl;
    std::filesystem::remove(testImgPath);

    // Test verifyGuestImage with valid key and vbmeta header
    std::string vbmetaPath = "/tmp/test_vbmeta.img";
    {
        std::ofstream out(vbmetaPath, std::ios::binary);
        VbmetaHeader hdr;
        hdr.magic[0] = 'A'; hdr.magic[1] = 'V'; hdr.magic[2] = 'B'; hdr.magic[3] = '0';
        hdr.rollbackIndex = 100;
        out.write(reinterpret_cast<const char*>(&hdr), sizeof(hdr));
    }
    {
        std::ofstream out(testImgPath, std::ios::binary);
        out << "AVB_IMAGE_CONTENT";
    }

    std::string pubKeyPath = "system/etc/security/avb/guest_root_key.pub";
    if (std::filesystem::exists(pubKeyPath)) {
        bool verified = AvbVerifier::verifyGuestImage(testImgPath, vbmetaPath, pubKeyPath, 100);
        assert(verified);
        std::cout << "Pass: verifyGuestImage succeeded with RSA-4096 public key." << std::endl;
    }

    std::filesystem::remove(vbmetaPath);
    std::filesystem::remove(testImgPath);

    std::cout << "PASS: AVB Verifier Test Executed Successfully." << std::endl;
    return 0;
}

