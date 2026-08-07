# Specification Analysis: Test Harness Requirements & Reporting Formats for Milestone M1-TEST

**Author**: `teamwork_preview_spec_miner` (M1-TEST Sub-Track 3)  
**Date**: 2026-08-06  
**Target Milestone**: M1-TEST (Test Infrastructure & Runner Harness)  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  

---

## 1. Executive Summary

Milestone M1-TEST establishes the complete test infrastructure and runner harness for the AOSP Dual-OS System ("一個 AOSP 產品，兩個隔離的執行環境，一個統一的使用者體驗"). The test suite operates under a strict **opaque-box, requirement-driven methodology**, verifying all 37 platform features (F-R1-001 through F-R5-014) across four testing tiers (totaling 425+ mandatory test cases).

This specification analysis defines the rigorous specifications for:
1. **Test Design Methodologies**: Category-Partition, Boundary Value Analysis (BVA), Pairwise Combinations, and End-to-End Workload Application Scenarios.
2. **Mandatory Test Case Schema**: 7-field strict structure (`ID`, `Tier`, `Feature`, `Setup`, `Execute`, `Verify`, `Teardown`).
3. **Runner Reporting Formats**: Real-time ANSI colorized console output, summary statistics, diagnostic failure boxes, and machine-readable exports (`report.json`, `junit.xml`).
4. **Publication Checklist (`TEST_READY.md`)**: Structure, gating rules, coverage matrix, and sign-off requirements.

---

## 2. Test Design Methodologies & Specifications

### 2.1 Category-Partition Specification

The Category-Partition method systematically decomposes the input domain of the AOSP Dual-OS system into discrete categories, choices, and properties to eliminate redundant combinations while guaranteeing complete equivalence class coverage.

#### Category & Choice Definitions

| Category ID | Dimension / Interface | Equivalence Classes (Choices) | Property / Constraint Flags |
|-------------|-----------------------|--------------------------------|-----------------------------|
| **CAT-VM-STATE** | `LinuxManagerService` Lifecycle State | `OFF`, `STARTING`, `RUNNING`, `SUSPENDED`, `ERROR` | `[property StateRunning]`, `[property StateOff]` |
| **CAT-VSOCK-PORT** | Virtio-vsock Connectivity | `PORT_5000_CTRL`, `PORT_5001_PTY`, `PORT_5002_WAYLAND`, `UNALLOCATED` | `[property VsockActive]` |
| **CAT-AUTH-TOKEN** | HMAC-SHA256 Auth Handshake | `VALID_SINGLE_USE_TOKEN`, `EXPIRED_TOKEN`, `MISMATCHED_HMAC`, `REPLAYED_TOKEN` | `[if StateStarting]`, `[error]` |
| **CAT-STORAGE-CE** | LUKS2 CE Key Credential | `CE_UNLOCKED` (User PIN entered), `CE_LOCKED` (Device locked), `INVALID_KEY` | `[property CeUnlocked]` |
| **CAT-IME-INPUT** | CJK IME Composing & Commit | `ASCII_TEXT`, `ZHUYIN_COMPOSING`, `CANGJIE_COMPOSING`, `PINYIN_COMPOSING`, `EMOJI_SUPPLEMENTARY`, `MALFORMED_UTF8` | `[property ImeActive]` |
| **CAT-TOUCH-MODE** | Terminal Touch State Machine | `SHELL_MODE` (Scroll/Select), `TUI_MOUSE_MODE` (Vim/tmux SGR), `TOUCHPAD_MODE` (Cursor control) | `[property TouchActive]` |
| **CAT-WAYLAND-WIN** | Wayland Window Forwarding | `SINGLE_WINDOW`, `FREEFORM_MULTI_WINDOW`, `SPLIT_SCREEN`, `RECENTS_OVERVIEW` | `[if StateRunning]` |
| **CAT-APPOPS-PERM** | Host Hardware Portal Access | `GRANTED_ALWAYS`, `GRANTED_ONCE`, `DENIED`, `PROMPT_PENDING` | `[property AppOpsChecked]` |
| **CAT-NET-POLICY** | Guest Network / VPN Isolation | `NORMAL_BRIDGE`, `ALWAYS_ON_VPN_CONNECTED`, `VPN_DISCONNECTED_FAIL_CLOSED` | `[property NetIsolated]` |
| **CAT-SELINUX-CTX** | Host SELinux Context | `linux_manager.te`, `linux_bridge.te`, `linux_portal.te`, `VIOLATING_DOMAIN` | `[property SELinuxEnforced]` |
| **CAT-OTA-SLOT** | EROFS Base Image Slot | `SLOT_A_ACTIVE`, `SLOT_B_ACTIVE`, `CORRUPTED_SLOT_TRIGGER_ROLLBACK` | `[property OtaValid]` |

#### Equivalence Class Combination Rules
1. **Valid Combination Rule**: A test case must combine choices from non-conflicting properties (e.g., `StateRunning` + `VsockActive` + `CeUnlocked` + `AppOpsChecked`).
2. **Single Constraint (`[error]`)**: Any choice marked `[error]` (e.g., `MISMATCHED_HMAC`, `MALFORMED_UTF8`, `VIOLATING_DOMAIN`) must be tested in isolation (exactly one error choice per Tier 2 boundary test case).

---

### 2.2 Boundary Value Analysis (BVA) Specification

Boundary Value Analysis tests system behavior at minimum, maximum, nominal, and out-of-bounds parameter boundaries.

```
       Out-of-Bounds Low      Minimum      Min+1       Nominal       Max-1       Maximum     Out-of-Bounds High
[ < Min ] <----------- | ---------- | --------- | ----------- | --------- | ---------- | -----------> [ > Max ]
```

#### Specific BVA Boundary Test Matrices

##### A. Virtio-vsock Port 5001 PTY Framing Header
- Header Format: `[SessionID (16 bytes)][Type (1 byte)][Length (4 bytes uint32)][Payload]`
- **SessionID Boundary**:
  - `Min - 1`: 15 bytes (Truncated Header -> Error: `INVALID_FRAME_HEADER`)
  - `Min`: 16 bytes (Valid)
  - `Max + 1`: 17 bytes (Overlong Header -> Error: `FRAME_DESYNC`)
  - `Boundary Pattern`: All `0x00`, All `0xFF`
- **Type Byte Boundary**:
  - Valid: `0x01` (DATA), `0x02` (RESIZE), `0x03` (PING)
  - Invalid / Edge: `0x00` (NULL_TYPE -> Error: `UNKNOWN_FRAME_TYPE`), `0x04` (Unassigned), `0xFF` (Max uint8 -> Error: `UNKNOWN_FRAME_TYPE`)
- **Length Field (uint32) Boundary**:
  - `0 bytes`: Empty Payload frame (Valid PING / ACK)
  - `1 byte`: Single character payload (Valid)
  - `4096 bytes`: Standard page buffer boundary (Valid)
  - `65535 bytes`: 64KB vsock packet boundary (Valid)
  - `65536 bytes`: Frame spanning multiple vsock buffers (Valid)
  - `4,294,967,295 bytes (2^32 - 1)`: Max uint32 (Payload buffer overflow attempt -> Error: `PAYLOAD_TOO_LARGE`)

##### B. CJK IME Composing Window & UTF-8 Commit Buffer
- **Composing String Length**:
  - `0 chars`: Null commit (No PTY write)
  - `1 char`: Single CJK character (e.g. `中` -> 3 bytes UTF-8 `0xE4 0xB8 0xAD`)
  - `255 chars`: Max single IME composition window length
  - `256 chars`: Oversized IME composition (Truncation / buffer flush)
  - `65,536 chars`: Extreme IME buffer injection (Stress -> Verify zero host crash)
- **UTF-8 Byte Encoding Boundaries**:
  - 1-byte ASCII (`0x41` = 'A')
  - 2-byte Latin (`0xC3 0xA9` = 'é')
  - 3-byte CJK (`0xE6 0xB8 0xAC` = '測')
  - 4-byte Supplementary / Emoji (`0xF0 0x9F 0x99 0x82` = '🙂')
  - Broken Sequences: Truncated 3-byte CJK (`0xE4 0xB8` missing 3rd byte -> Error: `INVALID_UTF8_SEQUENCE`)

##### C. Terminal Window Dimension (Resize Frame)
- **Columns (uint16) & Rows (uint16)**:
  - `0 x 0`: Invalid dimension -> Error: `INVALID_TERMINAL_SIZE`
  - `1 x 1`: Minimum valid terminal size
  - `80 x 24`: Standard VT100 size
  - `300 x 100`: High-density display
  - `65535 x 65535`: Max uint16 terminal dimension -> Edge case (Clamp to max grid memory allocation)

##### D. Memory & Performance SLO Boundaries
- **VM Cold Boot Time**: `Target < 3500 ms`. Boundary test at `3499 ms` (PASS) vs `3501 ms` (FAIL).
- **VM Warm Boot / Resume Time**: `Target < 800 ms` / `< 300 ms`.
- **Touch Latency**: `Target < 25 ms`.
- **Idle Guest Memory Allocation**: `Min 128MB`, `Target < 450MB`, `Max 2048MB`.

---

### 2.3 Pairwise (All-Pairs) Combinational Specification

Pairwise testing ensures that every 2-way combination of parameter states across interacting features is tested at least once. This achieves high bug coverage with a reduced test suite size (Tier 3 target: >= 37 test cases).

#### Primary 6-Factor Interaction Matrix

| Factor | Factor Name | Level 1 | Level 2 | Level 3 | Level 4 |
|--------|-------------|---------|---------|---------|---------|
| **F1** | VM State | `OFF` | `RUNNING` | `SUSPENDED` | `ERROR` |
| **F2** | AppOps Permission | `GRANTED` | `DENIED` | `PROMPT_PENDING` | N/A |
| **F3** | Network / VPN | `NORMAL_BRIDGE` | `ALWAYS_ON_VPN` | `VPN_DISCONNECTED` | N/A |
| **F4** | IME Touch Mode | `SHELL_MODE` | `TUI_MOUSE_MODE` | `TOUCHPAD_MODE` | N/A |
| **F5** | Wayland Window | `SINGLE_WINDOW` | `FREEFORM_RESIZE` | `RECENTS_HIDDEN` | N/A |
| **F6** | SELinux Context | `ENFORCING` | `PERMISSIVE` | `POLICY_VIOLATION` | N/A |

#### Generated Pairwise Test Coverage Matrix (Sample 10 of 37)

```
TC-T3-PAIR-001: [VM: RUNNING]   + [AppOps: GRANTED]  + [VPN: ALWAYS_ON_VPN]    + [IME: TUI_MOUSE]  + [Win: FREEFORM_RESIZE] + [SELinux: ENFORCING]
TC-T3-PAIR-002: [VM: RUNNING]   + [AppOps: DENIED]   + [VPN: NORMAL_BRIDGE]    + [IME: SHELL_MODE] + [Win: SINGLE_WINDOW]   + [SELinux: ENFORCING]
TC-T3-PAIR-003: [VM: RUNNING]   + [AppOps: PROMPT]   + [VPN: VPN_DISCONNECTED] + [IME: TOUCHPAD]   + [Win: RECENTS_HIDDEN]  + [SELinux: POLICY_VIOLATION]
TC-T3-PAIR-004: [VM: SUSPENDED] + [AppOps: GRANTED]  + [VPN: NORMAL_BRIDGE]    + [IME: TOUCHPAD]   + [Win: SINGLE_WINDOW]   + [SELinux: ENFORCING]
TC-T3-PAIR-005: [VM: SUSPENDED] + [AppOps: DENIED]   + [VPN: ALWAYS_ON_VPN]    + [IME: SHELL_MODE] + [Win: RECENTS_HIDDEN]  + [SELinux: PERMISSIVE]
TC-T3-PAIR-006: [VM: OFF]       + [AppOps: GRANTED]  + [VPN: VPN_DISCONNECTED] + [IME: SHELL_MODE] + [Win: SINGLE_WINDOW]   + [SELinux: ENFORCING]
TC-T3-PAIR-007: [VM: ERROR]     + [AppOps: DENIED]   + [VPN: NORMAL_BRIDGE]    + [IME: TUI_MOUSE]  + [Win: FREEFORM_RESIZE] + [SELinux: POLICY_VIOLATION]
...
(Guarantees 100% 2-way parameter pair coverage across 37 test cases)
```

---

### 2.4 Workload / System Scenario Testing Specification

Workload testing (Tier 4, target >= 18 test cases) evaluates multi-step, multi-feature end-to-end user workflows, long-running stability, resource contention, and fault recovery.

#### Mandatory Workload Scenarios

1. **WORKLOAD-01: Full Lifecycle CJK Development Workflow**
   - *Sequence*: Cold boot Debian VM -> Launch Native Terminal -> Input CJK Zhuyin commands (`注音測試`) -> Launch Vim via PTY -> Switch to Touch-to-SGR Mouse Mode -> Save file to LUKS2 CE `/home/user` -> Suspend VM -> Resume VM -> Verify file integrity.
2. **WORKLOAD-02: Linux GUI App Forwarding & Hardware Portal Access**
   - *Sequence*: Guest `.desktop` inotify watcher detects VS Code -> Launcher3 syncs shortcut -> Click shortcut -> Wayland forwarding via `Sommelier` + `virtio-gpu` -> App triggers XDG Camera Portal -> Android AppOps prompt appears -> Grant permission -> Camera2 stream renders in Linux GUI window -> Freeform resize window.
3. **WORKLOAD-03: Heavy Concurrency & Resource Contention**
   - *Sequence*: Run 3 Linux GUI Apps + 2 Terminal PTY sessions + High-throughput `virtiofs` file copy (10GB) -> Trigger Guest Memory Ballooning -> Verify system stability and zero host crash.
4. **WORKLOAD-04: Network VPN Fail-Closed & Reconnection**
   - *Sequence*: Connect Guest to Host VPN -> Verify network routing -> Force drop Host VPN connection -> Verify Guest network immediately enters `FAIL_CLOSED` state -> Re-establish VPN -> Verify network auto-restoration without packet leak.
5. **WORKLOAD-05: Guest Base Image OTA Watchdog Rollback**
   - *Sequence*: Flash corrupted `base_b.img` EROFS image -> Trigger boot -> Boot watchdog detects failure across 3 attempts -> Watchdog automatically rolls back bootloader slot to `base_a.img` -> Verify clean system startup.

---

## 3. Mandatory Test Case Structure Specification

Every test case in the suite MUST adhere strictly to a 7-field schema. Omission of any field or failure to comply with validation rules results in immediate runner validation rejection.

### 3.1 Strict 7-Field Schema Definition

```
+-----------------------------------------------------------------------------------+
| MANDATORY TEST CASE SCHEMA                                                       |
+-----------------------------------------------------------------------------------+
| 1. ID       : String (Regex: ^TC-T[1-4]-(FR[1-5]-\d{3}|PAIR|WORKLOAD)-\d{2,3}$)  |
| 2. Tier     : Enum ['Tier 1', 'Tier 2', 'Tier 3', 'Tier 4']                       |
| 3. Feature  : Array<String> (Reference to F-R1-001 through F-R5-014)              |
| 4. Setup    : Array<String> (Explicit pre-conditions & mock setup steps)         |
| 5. Execute  : Array<String> (Ordered execution steps with precise parameters)     |
| 6. Verify   : Array<String> (Expected outcomes, assertions, exit codes)          |
| 7. Teardown : Array<String> (Cleanup steps, state restoration, resource release)  |
+-----------------------------------------------------------------------------------+
```

### 3.2 Python Dataclass Reference Implementation

```python
from dataclasses import dataclass, field
from enum import Enum
from typing import List
import re

class TestTier(Enum):
    TIER_1 = "Tier 1: Feature Coverage"
    TIER_2 = "Tier 2: Boundary & Corner"
    TIER_3 = "Tier 3: Pairwise Combination"
    TIER_4 = "Tier 4: Application Scenario"

@dataclass
class TestCase:
    id: str
    tier: TestTier
    feature: List[str]
    setup: List[str]
    execute: List[str]
    verify: List[str]
    teardown: List[str]

    def validate(self) -> None:
        """Enforces schema integrity constraints."""
        pattern = r"^TC-T[1-4]-(FR[1-5]-\d{3}|PAIR|WORKLOAD)-\d{2,4}$"
        if not re.match(pattern, self.id):
            raise ValueError(f"Invalid Test Case ID format: '{self.id}'")
        if not self.feature:
            raise ValueError(f"TestCase '{self.id}' must specify at least one Feature ID.")
        if not self.setup or not self.execute or not self.verify or not self.teardown:
            raise ValueError(f"TestCase '{self.id}' has empty mandatory sections.")
```

### 3.3 Example Complete Test Case Specification (Markdown/JSON)

```json
{
  "id": "TC-T1-FR3-004-01",
  "tier": "Tier 1: Feature Coverage",
  "feature": ["F-R3-004"],
  "setup": [
    "Initialize mock vsock server listening on Port 5001",
    "Instantiate TerminalInputConnection attached to Native Surface Canvas",
    "Set IME composing state to ACTIVE"
  ],
  "execute": [
    "Inject Zhuyin composing sequence 'z' 'o' '6' ('注')",
    "Call TerminalInputConnection.commitText('注', 1)",
    "Flush byte stream serializer to vsock Port 5001"
  ],
  "verify": [
    "Verify vsock Port 5001 receives valid DATA frame (Type 0x01)",
    "Assert payload length equals 3 bytes",
    "Assert payload bytes match UTF-8 sequence [0xE4, 0xB8, 0xAD] ('注')",
    "Verify composing window state resets to IDLE"
  ],
  "teardown": [
    "Close vsock Port 5001 connection",
    "Reset TerminalInputConnection state",
    "Clear mock frame buffer"
  ]
}
```

---

## 4. Test Runner Reporting Format Specification

### 4.1 Console Colorized Output Specification

The test runner provides real-time streaming output using standard ANSI escape codes for enhanced developer readability.

#### ANSI Color Palette Definition

```
Color Name     ANSI Escape Code   Usage / Meaning
------------------------------------------------------------------------------------
Green          \033[32m           [PASS] Passed test cases & success summaries
Red            \033[31m           [FAIL] Failed test cases & error highlights
Yellow         \033[33m           [SKIP] Skipped / XFailed test cases
Cyan           \033[36m           [RUN]  Active running indicator & progress headers
Magenta        \033[35m           [WARN] Warnings, flaky test retries
Bold White     \033[1;37m         Section headers & table titles
Reset          \033[0m            Reset terminal attributes
```

#### Real-Time Streaming Output Format Example

```
================================================================================
AOSP DUAL-OS E2E TEST RUNNER v1.0.0
Target Environment: AOSP Mainline (Debian 12 ARM64 Guest)
================================================================================
[RUN] Discovering test cases... Found 425 test cases across 4 Tiers.

--- Tier 1: Feature Coverage (185 Test Cases) ---
[001/425] [RUN]  TC-T1-FR1-001-01 Framework API Namespace validation...
[001/425] [PASS] TC-T1-FR1-001-01 Framework API Namespace validation (4ms)
[002/425] [RUN]  TC-T1-FR3-004-01 Multi-stage CJK IME Commit (Zhuyin)...
[002/425] [PASS] TC-T1-FR3-004-01 Multi-stage CJK IME Commit (Zhuyin) (12ms)
...
[186/425] [RUN]  TC-T2-FR2-005-03 Vsock Token Mismatch Handshake...
[186/425] [FAIL] TC-T2-FR2-005-03 Vsock Token Mismatch Handshake (45ms)
```

---

### 4.2 Diagnostic Failure Logging Format

When a test case fails, the runner MUST output a structured diagnostic box containing complete failure forensics:

```
+-----------------------------------------------------------------------------------+
| [FAIL] DIAGNOSTIC REPORT: TC-T2-FR2-005-03                                        |
+-----------------------------------------------------------------------------------+
| Feature ID : F-R2-005 (HMAC-SHA256 Auth Handshake)                                |
| Tier       : Tier 2: Boundary & Corner                                            |
| File       : tests/e2e/test_r2_avf_storage.py:142                                |
|-----------------------------------------------------------------------------------|
| FAILURE REASON: AssertError: Expected vsock connection state REJECTED, got CONNECTED|
|-----------------------------------------------------------------------------------|
| EXPECTED VS ACTUAL DIFF:                                                          |
| - Expected: State.REJECTED (Error Code 0x401 Unauthorized)                       |
| + Actual  : State.CONNECTED (Session Token accepted)                              |
|-----------------------------------------------------------------------------------|
| SETUP SNAPSHOT:                                                                   |
|   Token Sent    : "INVALID_EXPIRED_TOKEN_HASH_12345"                              |
|   HMAC Expected : 8f9a2b1c...                                                     |
|   HMAC Received : 00000000...                                                     |
|-----------------------------------------------------------------------------------|
| RECENT LOGCAT BUFFERS (Last 5 lines):                                             |
|   08-06 14:02:11.102  1204  1230 E LinuxBridgeService: Token verification failed    |
|   08-06 14:02:11.103  1204  1230 W LinuxBridgeService: Fallback auth logic triggered |
|   08-06 14:02:11.105  1204  1230 E TestHarness: Handshake accepted invalid token  |
|-----------------------------------------------------------------------------------|
| REPRODUCE COMMAND:                                                                |
|   ./tests/e2e/run_tests.sh --id TC-T2-FR2-005-03 --verbose                       |
+-----------------------------------------------------------------------------------+
```

---

### 4.3 Summary Statistics Table Specification

At the end of execution, the runner presents a comprehensive summary table:

```
================================================================================
                     E2E TEST SUITE EXECUTION SUMMARY                           
================================================================================
Total Executed : 425
Passed         : 425
Failed         : 0
Skipped        : 0
Pass Rate      : 100.0%
Total Duration : 48.25 seconds

--------------------------------------------------------------------------------
TIER BREAKDOWN STATISTICS
--------------------------------------------------------------------------------
Tier Name                          Total   Passed   Failed   Skipped   Pass Rate
--------------------------------------------------------------------------------
Tier 1: Feature Coverage            185      185        0        0       100.0%
Tier 2: Boundary & Corner           185      185        0        0       100.0%
Tier 3: Pairwise Combinations        37       37        0        0       100.0%
Tier 4: Application Scenarios        18       18        0        0       100.0%
--------------------------------------------------------------------------------
TOTAL                               425      425        0        0       100.0%

--------------------------------------------------------------------------------
MODULE COVERAGE STATISTICS
--------------------------------------------------------------------------------
Module Scope                        Features  Tests   Passed  Pass Rate  Status
--------------------------------------------------------------------------------
R1: AOSP Framework & Core (001-005)    5        62      62     100.0%     PASS
R2: AVF Guest & Storage (001-005)      5        58      58     100.0%     PASS
R3: Terminal & IME (001-007)           7        84      84     100.0%     PASS
R4: Wayland GUI & Recents (001-006)    6        72      72     100.0%     PASS
R5: Portals, SELinux & OTA (001-014)  14       149     149     100.0%     PASS
--------------------------------------------------------------------------------
================================================================================
```

---

### 4.4 Machine-Readable Export Formats

#### A. JSON Report Export (`tests/e2e/reports/report.json`)

```json
{
  "suite": "AOSP Dual-OS E2E Test Suite",
  "timestamp": "2026-08-06T14:15:00Z",
  "summary": {
    "total": 425,
    "passed": 425,
    "failed": 0,
    "skipped": 0,
    "pass_rate": 100.0,
    "duration_seconds": 48.25
  },
  "tier_summary": {
    "tier_1": {"total": 185, "passed": 185, "failed": 0},
    "tier_2": {"total": 185, "passed": 185, "failed": 0},
    "tier_3": {"total": 37, "passed": 37, "failed": 0},
    "tier_4": {"total": 18, "passed": 18, "failed": 0}
  },
  "tests": [
    {
      "id": "TC-T1-FR3-004-01",
      "tier": "Tier 1: Feature Coverage",
      "feature": ["F-R3-004"],
      "status": "PASS",
      "duration_ms": 12,
      "error": null
    }
  ]
}
```

#### B. Standard JUnit XML Export (`tests/e2e/reports/junit.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuites name="AOSP Dual-OS E2E Suite" tests="425" failures="0" errors="0" time="48.25">
  <testsuite name="Tier 1: Feature Coverage" tests="185" failures="0" time="18.10">
    <testcase classname="F-R3-004.CJK_IME" name="TC-T1-FR3-004-01" time="0.012"/>
  </testsuite>
  <testsuite name="Tier 2: Boundary &amp; Corner" tests="185" failures="0" time="19.40">
    <testcase classname="F-R2-005.VsockAuth" name="TC-T2-FR2-005-01" time="0.015"/>
  </testsuite>
</testsuites>
```

---

## 5. Structure of TEST_READY.md Publication Checklist

Upon completion of Milestone M5-TEST, the publication marker file `TEST_READY.md` MUST be generated and committed to the workspace root (`/Users/iml1s/Documents/mine/aosp-linux/TEST_READY.md`).

### 5.1 Mandatory Document Layout

```markdown
# Publication Marker: AOSP Dual-OS Test Readiness (`TEST_READY.md`)

## Executive Summary & Verdict
- **Status**: PASSED / READY FOR PRODUCTION
- **Publication Timestamp**: 2026-08-06T15:00:00Z
- **Suite Pass Rate**: 100.0% (425 / 425 Test Cases Passed)
- **Target OS / Hypervisor**: AOSP Mainline (Android 15/16) + AVF (crosvm + KVM) + Debian 12 ARM64 Guest

---

## Tier Verification Checklist
- [x] **Tier 1: Feature Coverage**: 185 / 185 test cases passed (>= 5 tests per feature across F-R1-001 to F-R5-014).
- [x] **Tier 2: Boundary & Corner Cases**: 185 / 185 test cases passed (>= 5 tests per feature covering boundary/overflow/error states).
- [x] **Tier 3: Pairwise Combinations**: 37 / 37 test cases passed (100% 2-way cross-feature interaction coverage).
- [x] **Tier 4: Application Scenarios**: 18 / 18 test cases passed (End-to-end multi-step workload workflows).

---

## 37-Feature Coverage Matrix

| Feature ID | Feature Name | Tier 1 Count | Tier 2 Count | Tier 3/4 Coverage | Status |
|------------|--------------|-------------:|-------------:|-------------------|--------|
| F-R1-001 | Framework API Namespace | 5 | 5 | Covered in Pairwise | PASS |
| F-R1-002 | Framework AIDL Interfaces | 5 | 5 | Covered in Pairwise | PASS |
| F-R1-003 | SystemServer Integration | 5 | 5 | Covered in Workload-01 | PASS |
| F-R1-004 | Daemon Process Isolation | 5 | 5 | Covered in Workload-03 | PASS |
| F-R1-005 | State Machine Lifecycle | 5 | 5 | Covered in Pairwise | PASS |
| F-R2-001 | Non-Protected Debian VM | 5 | 5 | Covered in Workload-01 | PASS |
| F-R2-002 | 4-Layer Storage Image Layout | 5 | 5 | Covered in Workload-01 | PASS |
| F-R2-003 | LUKS2 CE Storage Encryption | 5 | 5 | Covered in Workload-01 | PASS |
| F-R2-004 | Vsock 3-Port Allocation | 5 | 5 | Covered in Pairwise | PASS |
| F-R2-005 | HMAC-SHA256 Auth Handshake | 5 | 5 | Covered in Pairwise | PASS |
| F-R3-001 | Native Surface Canvas Renderer | 5 | 5 | Covered in Workload-01 | PASS |
| F-R3-002 | libvterm Parser Integration | 5 | 5 | Covered in Workload-01 | PASS |
| F-R3-003 | TerminalInputConnection | 5 | 5 | Covered in Workload-01 | PASS |
| F-R3-004 | Multi-stage CJK IME Commit | 5 | 5 | Covered in Workload-01 | PASS |
| F-R3-005 | Touch Modes State Machine | 5 | 5 | Covered in Workload-01 | PASS |
| F-R3-006 | SGR Mouse Protocol Generator | 5 | 5 | Covered in Workload-01 | PASS |
| F-R3-007 | Vsock Port 5001 PTY Framing | 5 | 5 | Covered in Pairwise | PASS |
| F-R4-001 | Wayland Window Forwarding | 5 | 5 | Covered in Workload-02 | PASS |
| F-R4-002 | virtio-gpu dma-buf Sharing | 5 | 5 | Covered in Workload-02 | PASS |
| F-R4-003 | LinuxAppProxyActivity Task ID | 5 | 5 | Covered in Workload-02 | PASS |
| F-R4-004 | Freeform Multi-Window Resize | 5 | 5 | Covered in Workload-02 | PASS |
| F-R4-005 | .desktop Inotify Monitor Daemon | 5 | 5 | Covered in Workload-02 | PASS |
| F-R4-006 | Launcher3 Synthetic Shortcuts | 5 | 5 | Covered in Workload-02 | PASS |
| F-R5-001 | XDG Portal Camera Bridge | 5 | 5 | Covered in Workload-02 | PASS |
| F-R5-002 | XDG Portal Microphone Bridge | 5 | 5 | Covered in Pairwise | PASS |
| F-R5-003 | XDG Portal Location Bridge | 5 | 5 | Covered in Pairwise | PASS |
| F-R5-004 | AppOps Permission Prompt | 5 | 5 | Covered in Workload-02 | PASS |
| F-R5-005 | virtio-snd Audio Mapping | 5 | 5 | Covered in Pairwise | PASS |
| F-R5-006 | AudioFocus Policy Handler | 5 | 5 | Covered in Pairwise | PASS |
| F-R5-007 | virtiofs Bi-directional Sharing | 5 | 5 | Covered in Workload-03 | PASS |
| F-R5-008 | LinuxStorageProvider SAF Provider | 5 | 5 | Covered in Pairwise | PASS |
| F-R5-009 | SELinux Domain Policy Rules | 5 | 5 | Covered in Pairwise | PASS |
| F-R5-010 | SELinux neverallow Rules | 5 | 5 | Covered in Pairwise | PASS |
| F-R5-011 | CTS / VTS Compatibility | 5 | 5 | Covered in Workload-05 | PASS |
| F-R5-012 | EROFS Base Image A/B Layout | 5 | 5 | Covered in Workload-05 | PASS |
| F-R5-013 | AVB Key Signature Validation | 5 | 5 | Covered in Workload-05 | PASS |
| F-R5-014 | Boot Watchdog Rollback Engine | 5 | 5 | Covered in Workload-05 | PASS |

---

## Suite Reproducibility Instructions

```bash
# 1. Execute full E2E test suite
./tests/e2e/run_tests.sh --all

# 2. Verify machine-readable export integrity
python3 tests/e2e/runner.py --verify-artifacts
```

---

## Formal Gate Sign-Off Matrix

| Role | Agent / Reviewer | Status | Timestamp |
|------|------------------|--------|-----------|
| **Specification Miner** | `teamwork_preview_spec_miner` | APPROVED | 2026-08-06T15:00:00Z |
| **Test Writer** | `e2e_test_writer` | APPROVED | 2026-08-06T15:05:00Z |
| **Reviewer 1** | `e2e_reviewer_1` | APPROVED | 2026-08-06T15:10:00Z |
| **Reviewer 2** | `e2e_reviewer_2` | APPROVED | 2026-08-06T15:12:00Z |
| **Challenger 1** | `e2e_challenger_1` | APPROVED | 2026-08-06T15:15:00Z |
| **Challenger 2** | `e2e_challenger_2` | APPROVED | 2026-08-06T15:17:00Z |
| **Forensic Auditor** | `e2e_forensic_auditor` | APPROVED | 2026-08-06T15:20:00Z |
```

---

## 6. Discovered Features & Edge Cases

## Features Discovered
| # | Category | Feature | Description | Inputs | Outputs | Error Behavior | Discovered Via |
|---|----------|---------|-------------|--------|---------|----------------|----------------|
| 1 | Test Harness | Category-Partition Engine | Equivalence partition definition & constraint filtering | Choice flags & property rules | Valid combination set | Reject conflicting properties | Spec Mining |
| 2 | Test Harness | Boundary Value Generator | Min/Max/Nominal/Out-of-bounds parameter generator | Parameter domain bounds | Boundary test inputs | Capture overflow / truncation | Spec Mining |
| 3 | Test Harness | Pairwise Matrix Generator | 2-way combinational interaction matrix generator | Factor levels & constraints | Pairwise test cases | Reject invalid pairs | Spec Mining |
| 4 | Test Harness | Workload Scenario Executor | End-to-end multi-step workflow runner | Sequential workflow steps | System state trajectory | Fail-closed & state rollback | Spec Mining |
| 5 | Test Harness | Test Case Schema Validator | Strict 7-field schema compliance checker | Test case data dictionary | Validation PASS/FAIL | Rejects malformed test cases | Spec Mining |
| 6 | Reporter | Console Colorized Reporter | Real-time ANSI colored streaming console log | Execution events | Colorized console log | Red highlight box on fail | Spec Mining |
| 7 | Reporter | Diagnostic Failure Formatter | Structured error box with snapshot & logcat snippet | Failing assertion & log buffer | Formatted diagnostic box | Display diff & repro command | Spec Mining |
| 8 | Reporter | Export Generator (JSON/XML) | Machine-readable report summary generator | Suite execution result | `report.json`, `junit.xml` | Fail on unwriteable target | Spec Mining |
| 9 | Checklist | TEST_READY.md Publisher | Readiness sign-off document generator | Coverage matrix & pass rates | `TEST_READY.md` at root | Block publish if pass rate < 100% | Spec Mining |

## Edge Cases
| # | Feature | Input | Observed Behavior |
|---|---------|-------|-------------------|
| 1 | Vsock Framing (F-R3-007) | 15-byte frame header (missing 1 byte) | Desynchronizes stream parser; runner asserts `INVALID_FRAME_HEADER` error |
| 2 | Vsock Length (F-R3-007) | Payload Length set to `0xFFFFFFFF` (4GB-1) | Triggers buffer allocation guard; runner asserts `PAYLOAD_TOO_LARGE` without memory crash |
| 3 | CJK IME Commit (F-R3-004) | Broken UTF-8 byte sequence `0xE4 0xB8` | Rejects broken byte sequence; runner asserts `INVALID_UTF8_SEQUENCE` |
| 4 | IME Composing (F-R3-004) | 65,536-character composing string | Forces IME buffer truncation; runner verifies zero host memory leak |
| 5 | Terminal Resize (F-R3-005) | Terminal columns/rows set to `0 x 0` | Rejects resize frame; runner asserts `INVALID_TERMINAL_SIZE` |
| 6 | Terminal Resize (F-R3-005) | Terminal dimensions set to `65535 x 65535` | Clamps grid to max supported viewport bounds |
| 7 | LUKS CE Storage (F-R2-003) | Attempt mounting `/home/user` while CE key is LOCKED | Denies access; runner asserts `CE_STORAGE_LOCKED` error |
| 8 | HMAC Auth (F-R2-005) | Replaying previously used 256-bit single-use token | Rejects connection; runner asserts `TOKEN_REPLAY_REJECTED` |
| 9 | Guest VPN (F-R5-004) | Drop Host VPN while Guest is transmitting active TCP stream | Forces Guest traffic into `FAIL_CLOSED` block; runner asserts zero packet leakage |
| 10 | SELinux (F-R5-010) | `linux_manager` domain attempting write to `/system/bin/app_process` | SELinux policy blocks operation; runner asserts `NEVERALLOW_VIOLATION` |
| 11 | OTA Rollback (F-R5-014) | Inject corrupted `base_b.img` causing systemd startup crash | Boot watchdog counts 3 failures, rolls back boot slot to `base_a.img` |
| 12 | Schema Validation | Test case missing `Teardown` field | Test runner schema validator rejects test case during test discovery phase |
