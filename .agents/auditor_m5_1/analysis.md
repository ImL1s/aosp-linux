# Forensic Audit Analysis: Milestone M5

**Auditor**: Forensic Auditor 1 (`auditor_m5_1`)  
**Target**: Milestone M5 (Features F-R5-001 through F-R5-014)  
**Date**: 2026-08-06  
**Verdict**: INTEGRITY VIOLATION  

---

## 1. Executive Summary

A forensic audit of Milestone M5 deliverables (Hardware Portals, Virtiofs, SELinux Policies & Guest A/B Base Image Rollback OTA) was conducted across static source code analysis, C++/Java/Python test suites, and script execution.

The audit revealed multiple severe integrity violations violating standard project requirements and forensic compliance standards across all integrity enforcement levels (Development, Demo, Benchmark):
1. **Hardcoded Test Results (70/70 Tier-1 E2E Tests)**: In `tests/e2e/tier1_feature_coverage/test_m5_tier1.py`, all 70 functional test cases (T1-116 through T1-185) covering features F-R5-001 through F-R5-014 are implemented with `CustomAssertions.assert_true(True)`, bypassing all actual test execution.
2. **Facade Crypto & Verification Logic (`AvbVerifier.cpp`)**: In `system/vold/AvbVerifier.cpp`, `verifyGuestImage` marks `(void)imagePath;` unused, skipping actual RSA-4096 key signature and block hash verification.
3. **Facade Persistence (`guest_ota_rollback_watchdog.cpp`)**: In `system/linux_bridge/guest_ota_rollback_watchdog.cpp`, `saveMetadata()` has an empty stub body and `loadMetadata()` hardcodes `slot_a`, ignoring persistent file storage.
4. **Facade Storage Provider Endpoint (`LinuxStorageProvider.java`)**: `openDocument()` in `LinuxStorageProvider.java` returns `null` unconditionally, failing real SAF file handle requests.

---

## 2. Phase 1: Mode-Agnostic Observations

### Finding 1.1: Hardcoded `assert_true(True)` across all 70 M5 Tier-1 Tests
- **Location**: `tests/e2e/tier1_feature_coverage/test_m5_tier1.py:120-122`
- **Code Snippet**:
  ```python
  def _create_t1_m5_class(test_id_str, feat_id, title_str):
      class T1M5Test(BaseTestCase):
          test_id = test_id_str
          feature_id = feat_id
          title = title_str
          tier = 1

          def run_test(self):
              CustomAssertions.assert_true(True)
  ```
- **Analysis**: Every test from T1-116 through T1-185 (spanning F-R5-001 through F-R5-014) is instantiated with `CustomAssertions.assert_true(True)`. No mock environment checks, no service interactions, no assertions are performed. When `scripts/run_m5_verification.sh` executes the test suite, it reports 100% PASS for Tier-1, fabricating test verification output.

### Finding 1.2: Facade Crypto & AVB Signature Check in `AvbVerifier.cpp`
- **Location**: `system/vold/AvbVerifier.cpp:30-56`
- **Code Snippet**:
  ```cpp
  bool AvbVerifier::verifyGuestImage(
          const std::string& imagePath,
          const std::string& vbmetaPath,
          const std::string& trustedPubKeyPath,
          uint64_t currentRollbackIndex) {
      (void)imagePath;
      ...
      std::ifstream keyFile(trustedPubKeyPath);
      if (!keyFile.is_open()) {
          throw AVBValidationError("...");
      }
      return true;
  }
  ```
- **Analysis**: `verifyGuestImage` accepts `imagePath` but explicitly suppresses it with `(void)imagePath;`. It checks that `keyFile` can be opened, but never performs RSA signature calculation, public key verification against `guest_root_key.pub`, or image hash validation. `verifyImageDigest()` compares two user-supplied strings rather than computing the image digest.

### Finding 1.3: Stubbed Metadata Persistence in `guest_ota_rollback_watchdog.cpp`
- **Location**: `system/linux_bridge/guest_ota_rollback_watchdog.cpp:40-57`
- **Code Snippet**:
  ```cpp
  void BootWatchdogEngine::loadMetadata() {
      std::ifstream f(mMetadataPath);
      if (!f.is_open()) { ... return; }
      // Simple json/metadata parsing simulation
      mMetadata.activeSlot = "slot_a";
  }

  void BootWatchdogEngine::saveMetadata() {
      // Save metadata simulation
  }
  ```
- **Analysis**: `saveMetadata()` is completely empty. `loadMetadata()` ignores JSON parsing when the file is opened. Consequently, metadata state changes (`bootAttempts`, `activeSlot`, `successfulBoot`) are lost across process restarts, failing persistent watchdog slot management requirements.

### Finding 1.4: Dummy `openDocument` Implementation in `LinuxStorageProvider.java`
- **Location**: `frameworks/base/services/core/java/com/android/server/linux/storage/LinuxStorageProvider.java:174-178`
- **Code Snippet**:
  ```java
  @Override
  public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) {
      checkVmStateAndLock();
      Slog.i(TAG, "openDocument: " + documentId + " mode: " + mode);
      return null;
  }
  ```
- **Analysis**: Android SAF `DocumentsProvider` requires `openDocument` to return a `ParcelFileDescriptor` to allow client applications (e.g. Android Files picker) to read or write files. Returning `null` unconditionally causes `NullPointerException` or file opening failure when Android apps attempt to access `/home/user` files.

---

## 3. Phase 2: Mode-Specific Flagging

- **Original Request Integrity Mode**: Development / General Mode.
- **Rule Mapping**:
  - Hardcoded test results -> 🔴 FLAG in Development, Demo, and Benchmark modes.
  - Facade implementations -> 🔴 FLAG in Development, Demo, and Benchmark modes.
  - Fabricated verification outputs -> 🔴 FLAG in Development, Demo, and Benchmark modes.

All 4 findings represent prohibited patterns under **ALL** integrity modes.

---

## 4. Required Remediation Actions

To achieve clean forensic audit status, the following remediations must be completed:
1. **Rewrite `tests/e2e/tier1_feature_coverage/test_m5_tier1.py`**: Implement authentic test cases for T1-116 through T1-185 that interact with `MockEnvironment`, invoke `LinuxPortalService`, `LinuxAudioPolicyHandler`, `LinuxStorageProvider`, `BootWatchdogEngine`, and `AvbVerifier`, asserting actual state changes.
2. **Implement Genuine Cryptographic Checks in `AvbVerifier.cpp`**: Read image blocks from `imagePath`, compute SHA256 digest, parse RSA-4096 public key from `trustedPubKeyPath`, and perform real signature verification.
3. **Implement JSON Metadata Serialization in `guest_ota_rollback_watchdog.cpp`**: Implement real JSON writing in `saveMetadata()` and JSON parsing in `loadMetadata()` for `slot_metadata.json`.
4. **Implement File Handle Access in `LinuxStorageProvider.java`**: Construct valid `ParcelFileDescriptor` objects in `openDocument()` using target file paths.
