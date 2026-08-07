# Milestone M2 (R2) Empirical Challenge Handoff Report

## 1. Observation

Direct empirical inspection and code verification were conducted on `/Users/iml1s/Documents/mine/aosp-linux`:

1. **`guest_root_key.pub` Verification**:
   - Path: `system/etc/security/avb/guest_root_key.pub`
   - Parsed with `cryptography.hazmat.primitives.serialization`:
     - Key Type: RSA
     - Key Size: 4096 bits
   - File size: 800 bytes. Valid PEM public key.

2. **`vbmeta.img` RSA Signature & Auxiliary Key Inspection**:
   - Path: `build_out/guest_images/vbmeta.img` (File size: 300 bytes)
   - Command: `python3 .agents/challenger_m2_2/verify_m2_r2.py`
   - Verbatim Output:
     ```
     === Inspecting build_out/guest_images/vbmeta.img ===
     File size: 300 bytes
     Magic header: b'AVB0' (Expected: b'AVB0')
     Header struct breakdown:
       required_libavb_version_major: 1
       required_libavb_version_minor: 0
       authentication_data_block_size: 0
       auxiliary_data_block_size: 0
       algorithm_type: 1 (1=SHA256_RSA2048, 3=SHA256_RSA4096)
       rollback_index: 1000
       flags: 0
       [ALERT] authentication_data_block_size is 0 (No RSA signature in vbmeta!)
       [ALERT] auxiliary_data_block_size is 0 (No public key / descriptors in vbmeta!)
     ```
   - Script Source: `guest/scripts/init_storage_layout.sh:75`
     - Line 75: `python3 -c "import struct; f = open('${VBMETA_FILE}', 'wb'); f.write(b'AVB0' + struct.pack('<IIQQIQI', 1, 0, 0, 0, 1, 1000, 0) + b'\x00' * 256)"`

3. **LUKS2 Header Magic Inspection**:
   - Path: `build_out/guest_images/user_home.img` (File size: 5,242,880,000 bytes / 5000 MB)
   - Command: `python3 .agents/challenger_m2_2/verify_m2_r2.py`
   - Verbatim Output:
     ```
     === Inspecting LUKS2 Header in build_out/guest_images/user_home.img ===
     File size: 5,242,880,000 bytes (5000.00 MB)
     First 16 bytes: b'\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00'
     Valid LUKS/LUKS2 header magic present: False
       [ALERT] user_home.img is missing LUKS2 header magic (all zero bytes!)
     ```
   - Script Source: `guest/scripts/init_storage_layout.sh:35-36`
     - Lines 35-36 are commented out: `# cryptsetup luksFormat --type luks2 --cipher aes-xts-plain64 --key-size 512 "${HOME_IMG}"`.

4. **Image Sizes Check**:
   - `build_out/guest_images/base_rootfs.img`: 2,621,440,000 bytes (2500 MB) -> MATCH
   - `build_out/guest_images/custom_overlay.img`: 4,194,304,000 bytes (4000 MB) -> MATCH
   - `build_out/guest_images/user_home.img`: 5,242,880,000 bytes (5000 MB) -> MATCH
   - `build_out/guest_images/vm_state.snapshot`: 0 bytes (placeholder) -> MATCH
   - `build_out/guest_images/vm_config.json`: 927 bytes -> MATCH

5. **`AvbVerifier` C++ Struct Layout Padding Defect**:
   - Path: `system/vold/AvbVerifier.h:27-36`
   - Command: `clang++ -std=c++20 -I. -I/opt/homebrew/opt/openssl@3/include -L/opt/homebrew/opt/openssl@3/lib system/vold/AvbVerifier.cpp tests/unit/challenger_m2_r2_avb_test.cpp -lssl -lcrypto -o build_out/bin/challenger_m2_r2_avb_test && ./build_out/bin/challenger_m2_r2_avb_test`
   - Verbatim Output:
     ```
     sizeof(VbmetaHeader) unpacked (AvbVerifier.h): 56 bytes
     sizeof(PackedVbmetaHeader) packed: 44 bytes

     --- Reading via Unpacked VbmetaHeader (AvbVerifier.h) ---
       Magic: AVB0
       Major/Minor: 1.0
       Auth Data Size: 0
       Aux Data Size: 4294967296
       Algorithm Type: 1000
       Rollback Index: 0
       Flags: 0

     AvbVerifier::verifyGuestImage threw exception: AVBRollbackDenied: Package index 0 < device index 1000
     ```

6. **`AvbVerifier::verifyGuestImage` Logic Flaw**:
   - Source: `system/vold/AvbVerifier.cpp:66-67, 99-102`
     - Lines 66-67: `std::string actualDigest = calculateImageDigest(imagePath); (void)actualDigest;` -> Computed SHA-256 digest is discarded.
     - Lines 99-102: Only checks `EVP_PKEY_get_bits(pkey) == 4096`, does not verify image hash or RSA signature.

---

## 2. Logic Chain

1. **Observation 1 & 2** establish that `guest_root_key.pub` is a valid 4096-bit RSA key, but `vbmeta.img` was generated with hardcoded 0-byte authentication and auxiliary blocks (`auth_sz`=0, `aux_sz`=0) and `algorithm_type`=1 (`SHA256_RSA2048`). Therefore, `vbmeta.img` does not contain an RSA-4096 signature or public key matching `guest_root_key.pub`.
2. **Observation 3 & 4** establish that guest image sizes match specification (2500M, 4000M, 5000M), but `user_home.img` consists entirely of zero bytes without a valid LUKS2 header (`LUKS\xba\xbe`), because `cryptsetup luksFormat` was commented out in `init_storage_layout.sh`.
3. **Observation 5** establishes that `struct VbmetaHeader` in `system/vold/AvbVerifier.h` lacks `__attribute__((packed))`. Compiler padding increases struct size from 44 to 56 bytes, shifting field offsets when reading `vbmeta.img`. As a result, `rollbackIndex` reads as `0` instead of `1000`, causing `AvbVerifier::verifyGuestImage` to throw `AVBRollbackDenied: Package index 0 < device index 1000`.
4. **Observation 6** establishes that `AvbVerifier::verifyGuestImage` calculates `actualDigest` and discards it via `(void)actualDigest`, and checks only key bit length without verifying RSA-4096 signatures.
5. Connecting items 1 through 4 leads to the conclusion that AVB 2.0 guest image signing, key verification, and LUKS2 header formatting have critical defects that prevent secure image verification and LUKS container initialization.

---

## 3. Caveats

No caveats. All observations were empirically derived by running python inspection scripts, binary header analysis, and compiled C++ test executables on the exact workspace artifacts.

---

## 4. Conclusion

**Verdict: REQUEST_CHANGES**

The M2 guest image packaging and verification infrastructure fails empirical verification due to 4 critical defects:
1. `vbmeta.img` contains 0-byte signature/aux blocks and does not match `guest_root_key.pub`.
2. `user_home.img` lacks LUKS2 magic headers (all zero bytes).
3. `VbmetaHeader` in `AvbVerifier.h` lacks struct packing, causing `AvbVerifier::verifyGuestImage` to fail with `AVBRollbackDenied`.
4. `AvbVerifier::verifyGuestImage` discards image digests and skips RSA signature checks.

### Actionable Remediation Steps Required:
1. **Fix `VbmetaHeader` struct packing**: Add `__attribute__((packed))` to `struct VbmetaHeader` in `system/vold/AvbVerifier.h`.
2. **Enable LUKS2 formatting**: Uncomment `cryptsetup luksFormat` in `guest/scripts/init_storage_layout.sh` to initialize `user_home.img` with a valid LUKS2 header.
3. **Generate valid AVB 2.0 signed `vbmeta.img`**: Update `guest/scripts/init_storage_layout.sh` to construct a valid `vbmeta.img` with RSA-4096 (`algorithm_type` = 3), non-zero auxiliary public key metadata matching `guest_root_key.pub`, and a valid signature block.
4. **Fix `AvbVerifier::verifyGuestImage` logic**: Check `actualDigest` against `vbmeta` descriptors and execute `EVP_DigestVerify` using `guest_root_key.pub`.

---

## 5. Verification Method

To independently verify all findings:

1. **Verify `vbmeta.img` and `guest_root_key.pub`**:
   ```bash
   python3 .agents/challenger_m2_2/verify_m2_r2.py
   ```
   *Expected result*: Displays `auth_sz=0`, `aux_sz=0`, `LUKS2 header magic present: False`.

2. **Verify C++ `AvbVerifier` Struct Alignment Bug**:
   ```bash
   clang++ -std=c++20 -I. -I/opt/homebrew/opt/openssl@3/include -L/opt/homebrew/opt/openssl@3/lib system/vold/AvbVerifier.cpp tests/unit/challenger_m2_r2_avb_test.cpp -lssl -lcrypto -o build_out/bin/challenger_m2_r2_avb_test && ./build_out/bin/challenger_m2_r2_avb_test
   ```
   *Expected result*: Displays `sizeof(VbmetaHeader)` = 56 bytes and catches `AVBRollbackDenied: Package index 0 < device index 1000`.
