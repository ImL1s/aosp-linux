# Handoff Report — Explorer 2 (explorer_m6_framework)

## 1. Observation (觀察事實)

對 `tests/e2e/framework/` 及 `tests/e2e/runner.py` 基礎設施程式碼進行全面審查後，發現以下具體的 Dummy/Mock 物件、硬編碼與記憶體繞過事實：

### 1.1 `tests/e2e/framework/mock_env.py` 中的偽構造 (Fake Mocks & Hardcoded Bypasses)

1. **`MockVsockBridge` (Lines 6–49)**:
   - 使用純記憶體字典 `self.bound_ports: Dict[int, bool]`, `self.sent_packets: Dict[int, List[bytes]]`, `self.authenticated_sessions: Dict[str, bool]` 及 `self.used_tokens: set`。
   - `send()` 方法（Line 30–33）僅將 payload 追加（`append`）至記憶體列表：`self.sent_packets[port].append(payload)`。
   - 完全沒有使用 Python `socket` 模組，沒有開啟任何 `AF_UNIX` 或 `AF_VSOCK` 套接字 descriptor，也沒有進行真正的網路 I/O。

2. **`MockSystemServer` (Lines 50–87)**:
   - 純 Python 記憶體欄位 `self.vm_state = "OFF"`, `self.user_unlocked = False`, `self.ce_key_available = False`, `self.appops_permissions: Dict[str, Dict[str, str]]`, `self.audit_logs: List[str]`。
   - `check_appop()`（Line 82–83）僅進行字典查表 `self.appops_permissions.get(package_name, {}).get(op_name, "PROMPT")`。
   - 未與 Android 系統服務 `LinuxManagerService`、`AppOpsManager` 或 Linux `/sys`/`/proc` 檔案系統進行任何 IPC/AIDL 或 Binder 查詢。

3. **`MockSommelier` (Lines 88–110)**:
   - 使用 `self.active_surfaces: Dict[int, Dict[str, Any]]` 字典與 `self.next_surface_id` 計數器模擬 Wayland 視窗合成器。
   - `commit_frame()`（Line 104–106）僅在字典中對整數累加：`self.active_surfaces[surface_id]["committed_frames"] += 1`。
   - 未連接真實 Wayland Unix Domain Socket (`$XDG_RUNTIME_DIR/wayland-0` 或 `/tmp/sommelier-0`)。

4. **`MockXdgPortal` (Lines 111–137)**:
   - `request_location_access()`（Line 132–136）硬編碼回傳台北 101 座標與精度：`{"latitude": 25.0330, "longitude": 121.5654, "accuracy": 5.0}`。
   - 未發起真實 D-Bus `org.freedesktop.portal.Desktop` 請求。

5. **`MockEnvironment` (Lines 138–194)**:
   - **硬編碼 Storage Mounts**（Line 169–175）：`self.storage_mounts` 直接給定固定字典，包含 `"/home/user": {"device": "/dev/mapper/user_home_decrypted", "opts": "rw"}`。
   - **硬編碼 SELinux 規則**（Line 176–185）：`self.selinux_rules` 與 `self.neverallow_rules` 硬編碼 Python 字串陣列。
   - **硬編碼 AVB 金鑰與摘要**（Line 191–192）：`self.avb_key_valid = True`, `self.vbmeta_digest = "a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0"`。
   - **硬編碼 CTS 測試結果**（Line 193）：`self.cts_results: Dict[str, int] = {"passed": 170, "failed": 0}`。

### 1.2 `tests/e2e/runner.py` 測試執行器

- **Line 141**: `mock_env = MockEnvironment()` 全域實例化純記憶體 Mock 物件。
- **Line 144**: `test_instance = test_cls(mock_env=mock_env)`，將 Mock 傳入每個測試案例。
- 整個測試執行流程不涉及任何 IPC 傳輸、Binary 執行或系統服務查詢，測試報告直接由 `ReportFormatter` 寫入 `tests/e2e_report.json`。

### 1.3 `tests/e2e/framework/base_test.py`

- `BaseTestCase.__init__(self, mock_env=None)`: 強制綁定 `mock_env`（若未傳入則預設建立 `MockEnvironment()`），使所有繼承 `BaseTestCase` 的測試類別均被限縮在記憶體 Mock 操作中。

### 1.4 已具備可利用能力但被閒置的模組 (`command_runner.py`, `vsock_helper.py`)

- `command_runner.py`: 包含完整的 `CommandRunner.run()` (基於 `subprocess.run`)，可執行實體 Linux 命令並捕捉 stdout/stderr/exit_code，但現行測試案例完全未調用它來執行二進位檔。
- `vsock_helper.py`: 包含標準大端序二進位封包打包 (`VsockFramingHelper.create_frame()`) 與 HMAC-SHA256 驗證 (`HmacAuthHelper.compute_hmac()`)，邏輯完整但被 `MockVsockBridge` 的記憶體列表存取所閹割。

---

## 2. Logic Chain (推理邏輯鏈)

1. **觀察事實**: `mock_env.py` 與 `runner.py` 構成了一套純記憶體運行的「封閉假環境」。測試案例斷言的對象是 Python 記憶體字典 (例如 `mock_env.cts_results["passed"] == 170`) 或區域變數，而非實際的底層系統狀態。
2. **影響推導**: 當 AOSP 系統服務 (`LinuxManagerService`, `LinuxPortalService`)、橋接進程 (`linux_bridge`) 或底層工具 (SELinux `checkpolicy`, AVB `avbtool`, `vold`) 出現嚴重 Bug 甚至根本未編譯時，現有測試套件仍會回傳 100% 全數通過（PASS）。
3. **解決方針**: 必須將測試框架從「記憶體模擬」徹底轉型為「真實系統互動能力 (Real System Capabilities)」，包含：
   - 使用真實 Unix Socket / AF_VSOCK 進行 Socket I/O。
   - 使用 `CommandRunner` 執行實體 Linux 二進位檔 (`checkpolicy`, `avbtool`, `mountpoint`, `findmnt`)。
   - 透過 `/proc` / `/sys` 與 `dumpsys` / `cmd` 進行真實系統服務與掛載點斷言。

---

## 3. Caveats (注意事項與假設)

1. **無頭 CI 環境適應性 (Headless/CI Socket Harness)**:
   - 在沒有真實 QEMU 虛擬機或真實硬體的獨立 Linux/macOS CI 執行階段，測試套件需包含 **Socket Test Harness**（在測試線程中啟動真實 `socket.socket(AF_UNIX)` 監聽器），以確保 Socket 發送、二進位封包序列化、HMAC 密碼學與通訊協定握手皆在真實 OS kernel socket descriptor 上運作，而非退化為記憶體串列操作。
2. **系統二進位工具存在性與檢查 (Binary Existence Checks)**:
   - 對於 `checkpolicy` 或 `avbtool` 等工具，框架應透過 `CommandRunner` 執行實際命令並檢視傳回碼與輸出，若工具缺失應明確回傳可修復的錯誤訊息，而非硬編碼 `exit_code = 0` 欺瞞 CI。

---

## 4. Concrete Refactoring Plan & Detailed Class/Method Specification (重構方案與類別/方法細節)

### 4.1 框架架構設計 (Target Framework Architecture)

將 `mock_env.py` 替換/重構為 **`real_env.py`**，並拆分為三個專用互動模組：

```
tests/e2e/framework/
├── base_test.py           # 重構：支援 SystemEnvironment
├── real_env.py            # 新增/重構：取代 mock_env.py，提供 SystemEnvironment
├── socket_harness.py      # 新增：提供真實 AF_UNIX / AF_VSOCK 本地 Socket 傳送/接收與 Harness 服務
├── system_inspector.py    # 新增：提供二進位執行、/proc/mounts 解析、dumpsys/cmd 與 SELinux 檢查
├── vsock_helper.py        # 保留並強化：二進位封包序列化與 HMAC 驗證
├── command_runner.py      # 保留並強化：實體命令執行器
└── assertions.py          # 擴充：真實 IPC 回應與系統狀態斷言
```

### 4.2 重構與改寫之類別與方法明細 (Exact Classes & Methods to Refactor/Rewrite)

#### 1. 廢除 `MockVsockBridge` -> 改寫為 `RealVsockBridge` (`real_env.py` / `socket_harness.py`)
- **檔案位置**: `tests/e2e/framework/socket_harness.py`
- **類別名稱**: `RealVsockBridge`
- **關鍵方法細節**:
  - `bind_unix_socket(socket_path: str) -> socket.socket`:
    - 呼叫 `s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)`。
    - 若 socket 檔案已存在則 `os.unlink(socket_path)`。
    - 執行 `s.bind(socket_path)` 與 `s.listen(5)`。
  - `connect_unix_socket(socket_path: str, timeout: float = 5.0) -> socket.socket`:
    - 呼叫 `s.connect(socket_path)` 建立實體 socket 連接。
  - `send_vsock_frame(sock: socket.socket, session_id: bytes, packet_type: VsockPacketType, payload: bytes)`:
    - 使用 `VsockFramingHelper.create_frame(session_id, packet_type, payload)` 序列化。
    - 調用 `sock.sendall(frame)` 透過作業系統 socket 傳送二進位流。
  - `recv_vsock_frame(sock: socket.socket) -> Tuple[bytes, VsockPacketType, bytes]`:
    - 先呼叫 `sock.recv(VsockFramingHelper.HEADER_SIZE)` 讀取 21 位元組標頭。
    - 使用 `VsockFramingHelper.parse_header()` 解析出 payload 長度 $N$。
    - 再呼叫 `sock.recv(N)` 讀取完整 Payload，並回傳 `(session_id, packet_type, payload)`。
  - `perform_hmac_handshake(sock: socket.socket, secret: bytes) -> bool`:
    - 生成 32 位元組隨機 Token，發送 Handshake 封包，讀取對端傳回之 HMAC 簽名並使用 `HmacAuthHelper.verify_hmac()` 驗證。

#### 2. 廢除 `MockSystemServer` -> 改寫為 `RealSystemServerInspector` (`system_inspector.py`)
- **檔案位置**: `tests/e2e/framework/system_inspector.py`
- **類別名稱**: `RealSystemServerInspector`
- **關鍵方法細節**:
  - `query_vm_process_status() -> Dict[str, Any]`:
    - 執行 `CommandRunner.run("pgrep -fl linux_manager")` 或查詢 `/proc/[pid]/status`，驗證進程 PID 與記憶體狀態。
  - `check_appop_real(package_name: str, op_name: str) -> str`:
    - 執行 `CommandRunner.run(f"cmd appops get {package_name} {op_name}")` 或 `dumpsys appops` 並解析 CLI 回應字串（如 `ALLOW`, `DENY`, `IGNORE`）。
  - `query_selinux_avc_denials() -> List[str]`:
    - 執行 `CommandRunner.run("dmesg | grep avc")` 或讀取 `/var/log/audit/audit.log` 獲取真實 SELinux 拒絕紀錄。
  - `check_storage_ce_unlocked(user_id: int = 0) -> bool`:
    - 執行 `CommandRunner.run(f"vdc cryptfs isunlocked")` 或檢查 `/data/system/users/{user_id}` 掛載狀態。

#### 3. 廢除 `MockSommelier` -> 改寫為 `RealWaylandInspector` (`system_inspector.py`)
- **檔案位置**: `tests/e2e/framework/system_inspector.py`
- **類別名稱**: `RealWaylandInspector`
- **關鍵方法細節**:
  - `verify_wayland_socket_active(socket_path: str = None) -> bool`:
    - 若未指定則預設使用 `os.environ.get("XDG_RUNTIME_DIR", "/tmp") + "/wayland-0"`。
    - 檢查 `os.path.exists(socket_path)`，並嘗試建立 `AF_UNIX` 連接發送 Wayland handshake 標頭。

#### 4. 廢除 `MockXdgPortal` -> 改寫為 `RealDbusPortalInspector` (`system_inspector.py`)
- **檔案位置**: `tests/e2e/framework/system_inspector.py`
- **類別名稱**: `RealDbusPortalInspector`
- **關鍵方法細節**:
  - `send_dbus_portal_request(interface: str, method: str, args: list) -> CommandResult`:
    - 執行 `CommandRunner.run(f"busctl --user call org.freedesktop.portal.Desktop /org/freedesktop/portal/desktop {interface} {method} ...")` 驗證真實 D-Bus 回應。

#### 5. 廢除 `MockEnvironment` -> 建立 `SystemEnvironment` (`real_env.py`)
- **檔案位置**: `tests/e2e/framework/real_env.py`
- **類別名稱**: `SystemEnvironment`
- **關鍵方法細節**:
  - `__init__(self)`: 初始化 `RealVsockBridge`, `RealSystemServerInspector`, `RealWaylandInspector`, `RealDbusPortalInspector`。
  - `get_real_mounts() -> Dict[str, Dict[str, str]]`:
    - 執行 `CommandRunner.run("findmnt -J || cat /proc/mounts")` 並解析真實 JSON/純文字掛載表。
  - `compile_and_verify_selinux(policy_file: str) -> CommandResult`:
    - 執行 `CommandRunner.run(f"checkpolicy -M -c 30 -o /dev/null {policy_file}")` 進行真實 SELinux 策略編譯驗證。
  - `verify_avb_image(image_path: str) -> CommandResult`:
    - 執行 `CommandRunner.run(f"avbtool verify_image --image {image_path}")` 獲得真正的驗證回傳碼。
  - `execute_cts_test_module(module_name: str) -> CommandResult`:
    - 執行真實 CTS/VTS 二進位測試並解析其產出之 XML 結果，替代硬編碼 `{"passed": 170, "failed": 0}`。

#### 6. 重構 `runner.py` 與 `base_test.py`
- **`base_test.py`**:
  - 修改 `BaseTestCase.__init__(self, env=None)`，使其接受 `SystemEnvironment` 實例。
- **`runner.py`**:
  - 將 Line 141 的 `MockEnvironment()` 替換為 `SystemEnvironment()`。
  - 新增可選參數 `--socket-harness`：當在無虛擬機 CI 環境執行時，自動開啟背景 `LocalSocketHarnessThread` 監聽 AF_UNIX 套接字，配合測試案例進行真正的二進位 Socket I/O 發送與接收驗證。

---

## 5. Verification Method (獨立驗證方法)

為獨立驗證重構後的框架具備真實 IPC 與系統調用能力，請執行以下步驟：

1. **框架與模組加載驗證 (Module Import Verification)**:
   ```bash
   python3 -c "
   from framework.real_env import SystemEnvironment
   from framework.socket_harness import RealVsockBridge
   from framework.system_inspector import RealSystemServerInspector
   print('Framework modules loaded successfully!')
   "
   ```

2. **真實 AF_UNIX Socket 傳送/接收驗證 (Real Socket I/O Test)**:
   ```bash
   python3 -c "
   import socket, os
   from framework.vsock_helper import VsockFramingHelper, VsockPacketType
   from framework.socket_harness import RealVsockBridge

   sock_path = '/tmp/test_vsock_verify.sock'
   if os.path.exists(sock_path): os.unlink(sock_path)
   
   server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
   server.bind(sock_path)
   server.listen(1)
   
   client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
   client.connect(sock_path)
   conn, _ = server.accept()
   
   session_id = b'1234567890123456'
   frame = VsockFramingHelper.create_frame(session_id, VsockPacketType.DATA, b'REAL_IPC_DATA')
   client.sendall(frame)
   
   rx_data = conn.recv(1024)
   parsed_sid, parsed_type, parsed_payload = VsockFramingHelper.parse_frame(rx_data)
   assert parsed_payload == b'REAL_IPC_DATA'
   print('Real Socket Frame Verification PASSED!')
   
   client.close()
   conn.close()
   server.close()
   os.unlink(sock_path)
   "
   ```

3. **實體系統命令與掛載檢測驗證 (Command Execution Test)**:
   ```bash
   python3 -c "
   from framework.command_runner import CommandRunner
   res = CommandRunner.run('cat /proc/mounts || findmnt')
   assert res.exit_code == 0
   assert len(res.stdout) > 0
   print('CommandRunner Real System Inspection PASSED!')
   "
   ```

4. **測試執行器整合驗證 (Runner Integration Test)**:
   ```bash
   python3 tests/e2e/runner.py --tier 1 --list
   ```
