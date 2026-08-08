# Milestone M3 (Real Vsock Socket Connect & Session ID - R3) 端到端整合與介面合約調查報告

## 執行摘要

本報告針對 **Milestone M3 (Real Vsock Socket Connect & Session ID - R3)** 進行完整的端到端整合調查、構建目標分析、測試套件盤點以及介面合約與程式碼邊界驗證。調查確認了 M3 的核心缺陷、構建與測試目標 setup，並為後續 Worker 提供明確的實作步驟與驗證指令。

---

## 1. 構建檔案 (Build Files) 與 Target 配置

本專案中與 `LinuxTerminal` App、系統服務 (`LinuxManagerService`) 及原生 bridge/daemon 相關的構建檔案如下：

| 構建檔案路徑 | 模組名稱 / Target | 類型 | 說明 |
|---|---|---|---|
| `/Users/iml1s/Documents/mine/aosp-linux/Android.bp` | `android.system.linux` | `java_sdk_library` | 定義 AOSP Linux 雙系統 Core/Framework AIDL 介面與系統包 |
| `/Users/iml1s/Documents/mine/aosp-linux/Android.bp` | `services.linux` | `java_library` | 編譯 Framework 系統服務（包含 `LinuxManagerService.java`） |
| `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/Android.bp` | `LinuxTerminal` | `android_app` | 終端機 Android 應用程式（包含 `VsockTerminalClient`, `TerminalView`, `LinuxAppProxyActivity`） |
| `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/jni/Android.bp` | `libvterm_jni` | `cc_library_shared` | 終端機 JNI 原生解析與渲染庫（`libvterm`, `sgr_mouse_generator`, `pty_framing_handler`） |
| `/Users/iml1s/Documents/mine/aosp-linux/system/linux_bridge/Android.bp` | `linux_bridge` | `cc_binary` | Host 端原生 daemon（負責處理 local socket 與 vsock server 連線） |
| `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/Cargo.toml` | `android-bridge-agent` | `rust binary` | Guest Linux 內部 agent（監聽 Port 5000/5001/5002） |

---

## 2. 測試套件 (Test Suites) 與 Test Runner 盤點

專案已具備完整的單元測試、實證測試（Empirical/Stress Test）及端到端（E2E）測試框架：

### (1) Java 單元與實證測試套件
- **`tests/unit/TerminalAppUnitTest.java`**: 驗證 `TerminalView`, CJK IME (`CjkComposingTextManager`), Touchpad 控制器以及 `VsockTerminalClient` 之 Loopback Socket 傳輸與 Framing 解析。
- **`tests/unit/ChallengerM3EmpiricalTest.java`**: 驗證 `TerminalInputConnection` 前向刪除 (`deleteSurroundingText(0, 1)` High-Frequency Commit) 以及組字區取消。
- **`tests/unit/ChallengerM3RepEmpiricalTest.java`**: 實證併發 Session 與 Framer 邊界測試。
- **`tests/unit/LinuxManagerServiceTest.java`**: 驗證 `LinuxManagerService` 的 AIDL 介面及 Session 管理邏輯。
- **`tests/unit/TouchpadVsockStressTest.java`**: 驗證觸控板模式與 vsock 封包高頻壓力測試。

### (2) Native C++ 測試二進位檔
- **`tests/unit/m3_native_terminal_test.cpp`** (編譯產物: `tests/unit/m3_native_terminal_test_bin`)
- **`tests/unit/m3_native_challenger2_stress.cpp`** (編譯產物: `tests/unit/m3_native_challenger2_stress_bin`)
- **`tests/unit/challenger_m3_1_empirical_test.cpp`**

### (3) 端到端 (E2E) Test Runner (`tests/e2e/runner.py`)
- **啟動腳本**: `./tests/e2e/run_tests.sh` 或 `python3 tests/e2e/runner.py`
- **M3 功能測試範圍 (Tier 1)**: `F-R3-001` 至 `F-R3-007` (共 35 個測試，`T1-51` ~ `T1-85`)
- **M3 邊界測試範圍 (Tier 2)**: `F-R3-001` 至 `F-R3-007` (共 35 個測試，`T2-51` ~ `T2-85`)
- **AOSP Target 測試指令**: `atest LinuxTerminalTests`, `atest FrameworksServicesTests:LinuxManagerServiceTest`

---

## 3. 檔案邊界與寫入權限 (File Boundaries & Write Ownership)

為確保多 Agent 協作不發生程式碼衝突，特定檔案的寫入權限劃分如下：

### M3 Worker 擁有且可修改的檔案：
1. `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java`
2. `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java`
3. `/Users/iml1s/Documents/mine/aosp-linux/frameworks/base/services/core/java/com/android/server/linux/LinuxManagerService.java` (Session ID 產生與介面對齊部分)

### 禁止 M3 Worker 修改的檔案邊界：
- `guest/bridge-agent/*` (由 M2 Worker 擁有)
- `LinuxWindowBridgeService.java` / `LinuxAppProxyActivity.java` (由 M4 Worker 擁有)
- `LinuxPortalService.java` / `LinuxStorageProvider.java` (由 M5 Worker 擁有)

---

## 4. 介面合約與缺陷分析 (Interface Alignment & Defect Analysis)

經詳細讀取原始碼，發現 M3 涉及之四個關鍵元件存在以下介面不對齊與 deterministic 缺陷：

### 缺陷一：`VsockTerminalClient.java` 缺少真實 `AF_VSOCK` 的 `Os.connect(...)` 系統呼叫
- **位置**: `VsockTerminalClient.java:33`
- **現象**: 程式碼呼叫 `mSocketFd = Os.socket(AF_VSOCK, OsConstants.SOCK_STREAM, 0);` 後，直接建立 `FileInputStream` 與 `FileOutputStream`，完全未執行 `Os.connect(mSocketFd, ...)`。
- **影響**: 在 Linux/Android 系統上，未連線的 socket 執行讀寫會立即拋出 `ENOTCONN` (Socket is not connected, errno 57/107)。
- **修復方案**: 在 `connect(int guestCid, byte[] sessionId, listener)` 中，構造指向 `(AF_VSOCK=40, port=5001, cid=guestCid)` 的 socket 地址（如 `VmSocketAddress` 或 JNI/POSIX `sockaddr_vm` 反射），並呼叫 `Os.connect(mSocketFd, address)`。

### 缺陷二：`TerminalView.java` 使用硬編碼 Session ID
- **位置**: `TerminalView.java:49`
- **現象**: `private byte[] mSessionId = "0123456789abcdef".getBytes();` 硬編碼為靜態字串，且在 `onAttachedToWindow()` 中未與 `LinuxManagerService` 互動即開啟連線。
- **影響**: 無法支援動態多 Session 隔離，違反動態 Session 管理規範。
- **修復方案**: `TerminalView` 於載入時透過 `ILinuxManager` 呼叫 `createTerminalSession(width, height, callback)` 取得動態發行的 16 位元組 Session ID。

### 缺陷三：`LinuxManagerService.java` 產生的 Session ID 長度與 `VsockPtyFramer.java` 規格不符
- **位置**: `LinuxManagerService.java:392` vs `VsockPtyFramer.java:HEADER_SIZE`
- **現象**: `LinuxManagerService` 產生 `"session_1001"` (12 位元組ASCII字串)，而 `VsockPtyFramer.java` 強制要求 Session ID 長度必須 **精確等於 16 位元組** (`if (sessionId == null || sessionId.length != 16) throw new IllegalArgumentException(...)`)。
- **影響**: 若傳入 12 位元組的 Session ID，`VsockPtyFramer.serializeFrame` 會拋出 `IllegalArgumentException`。
- **修復方案**: 修改 `LinuxManagerService.java` 中的 `createTerminalSession` 方法，使其發行精確為 16 位元組的 ASCII Session ID（例如 `"sess_000000001001"`）或 32 字元的 Hex 字串轉換為 16 位元組 Byte 陣列。

### 缺陷四：雙模式連線支援 (Real AF_VSOCK vs Loopback Socket Test)
- `VsockTerminalClient.java` 應保留 `connectSocket(java.net.Socket socket, ...)` 供單元測試 (Mock Environment) 使用，同時健全化 `connect(int guestCid, ...)` 供真實 VM 使用。

---

## 5. 建議 Worker 實作步驟與驗證指令

### 實作步驟 (Implementation Steps):
1. **修改 `LinuxManagerService.java`**:
   - 更新 `createTerminalSession(int width, int height, ILinuxTerminalCallback callback)`，改為產生精確 16 位元組格式的 Session ID（例如 `String.format("sess_%012d", ++mNextSessionId)`，長度恰為 16 專屬 ASCII 位元組）。
2. **修改 `TerminalView.java`**:
   - 於 View 初始化或附著時，取得 `ILinuxManager` 服務代理，呼叫 `createTerminalSession(mColumns, mRows, callback)` 取得動態 16 位元組 Session ID，並帶入 `connectVsock(GUEST_CID, dynamicSessionId)`。
3. **修改 `VsockTerminalClient.java`**:
   - 在 `connect(int guestCid, byte[] sessionId, listener)` 中，呼叫 `Os.connect(mSocketFd, new VmSocketAddress(VPORT_PTY, guestCid))`（或相應 POSIX `sockaddr_vm` 連線結構），建立真實的 AF_VSOCK Socket 傳輸通道。
4. **維護與擴充單元測試**:
   - 確保 `TerminalAppUnitTest.java` 與 `ChallengerM3EmpiricalTest.java` 覆蓋動態 Session ID 及 Socket Connect。

### 驗證指令 (Verification Commands):
```bash
# 1. 執行 Tier 1 M3 功能測試 (35/35 通過)
cd /Users/iml1s/Documents/mine/aosp-linux && python3 tests/e2e/runner.py --tier 1 --feature F-R3

# 2. 執行 Tier 2 M3 邊界測試 (35/35 通過)
cd /Users/iml1s/Documents/mine/aosp-linux && python3 tests/e2e/runner.py --tier 2 --feature F-R3

# 3. 執行 Java 單元與實證測試
javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name '*.java') tests/unit/TerminalAppUnitTest.java && java -classpath /tmp/m3_classes tests.unit.TerminalAppUnitTest

# 4. 執行 AOSP atest
atest LinuxTerminalTests
```
