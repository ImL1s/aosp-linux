#include "system/vold/AvbVerifier.h"
#include <iostream>
#include <fstream>
#include <vector>

using namespace android::vold;

struct __attribute__((packed)) PackedVbmetaHeader {
    char magic[4]; // "AVB0"
    uint32_t requiredLibavbVersionMajor;
    uint32_t requiredLibavbVersionMinor;
    uint64_t authenticationDataBlockSize;
    uint64_t auxiliaryDataBlockSize;
    uint32_t algorithmType;
    uint64_t rollbackIndex;
    uint32_t flags;
};

int main() {
    std::cout << "=== Empirical AVB 2.0 & Key Verification Test ===" << std::endl;

    std::cout << "sizeof(VbmetaHeader) unpacked (AvbVerifier.h): " << sizeof(VbmetaHeader) << " bytes" << std::endl;
    std::cout << "sizeof(PackedVbmetaHeader) packed: " << sizeof(PackedVbmetaHeader) << " bytes" << std::endl;

    std::string baseImgPath = "build_out/guest_images/base_rootfs.img";
    std::string vbmetaPath = "build_out/guest_images/vbmeta.img";
    std::string pubKeyPath = "system/etc/security/avb/guest_root_key.pub";

    // Read with unpacked struct (current AvbVerifier implementation)
    {
        std::ifstream vbFile(vbmetaPath, std::ios::binary);
        VbmetaHeader hdr;
        vbFile.read(reinterpret_cast<char*>(&hdr), sizeof(hdr));
        std::cout << "\n--- Reading via Unpacked VbmetaHeader (AvbVerifier.h) ---" << std::endl;
        std::cout << "  Magic: " << std::string(hdr.magic, 4) << std::endl;
        std::cout << "  Major/Minor: " << hdr.requiredLibavbVersionMajor << "." << hdr.requiredLibavbVersionMinor << std::endl;
        std::cout << "  Auth Data Size: " << hdr.authenticationDataBlockSize << std::endl;
        std::cout << "  Aux Data Size: " << hdr.auxiliaryDataBlockSize << std::endl;
        std::cout << "  Algorithm Type: " << hdr.algorithmType << std::endl;
        std::cout << "  Rollback Index: " << hdr.rollbackIndex << std::endl;
        std::cout << "  Flags: " << hdr.flags << std::endl;
    }

    // Read with packed struct
    {
        std::ifstream vbFile(vbmetaPath, std::ios::binary);
        PackedVbmetaHeader hdr;
        vbFile.read(reinterpret_cast<char*>(&hdr), sizeof(hdr));
        std::cout << "\n--- Reading via PackedVbmetaHeader ---" << std::endl;
        std::cout << "  Magic: " << std::string(hdr.magic, 4) << std::endl;
        std::cout << "  Major/Minor: " << hdr.requiredLibavbVersionMajor << "." << hdr.requiredLibavbVersionMinor << std::endl;
        std::cout << "  Auth Data Size: " << hdr.authenticationDataBlockSize << std::endl;
        std::cout << "  Aux Data Size: " << hdr.auxiliaryDataBlockSize << std::endl;
        std::cout << "  Algorithm Type: " << hdr.algorithmType << std::endl;
        std::cout << "  Rollback Index: " << hdr.rollbackIndex << std::endl;
        std::cout << "  Flags: " << hdr.flags << std::endl;
    }

    // 2. Run AvbVerifier::verifyGuestImage
    try {
        bool res = AvbVerifier::verifyGuestImage(baseImgPath, vbmetaPath, pubKeyPath, 1000);
        std::cout << "\nAvbVerifier::verifyGuestImage result: " << (res ? "TRUE (Passed)" : "FALSE") << std::endl;
    } catch (const std::exception& e) {
        std::cout << "\nAvbVerifier::verifyGuestImage threw exception: " << e.what() << std::endl;
    }

    return 0;
}
