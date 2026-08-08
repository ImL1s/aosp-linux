# Review & Verification Report — Round 4 (Reviewer 2)

## 1. 觀察結果 (Observation)

本 Reviewer 2 針對 Round 4 驗證關卡進行獨立程式碼審查、資安協定驗證、通訊協定標頭分析與獨立測試執行，觀察事實如下：

### 1. 64-byte `AuthHandshakePayload` 常數時間 HMAC 驗證與 Raw Secret 清除
- **檔案**：`guest/bridge-agent/src/auth.rs` (Lines 57-79, 161-191, 227-259)
- **程式碼事實**：
  - `perform_handshake` 精確讀取 64 位元組 payload (`payload_buf[0..32]` 為 token nonce，`payload_buf[32..64]` 為 HMAC 簽名)。
  - `verify_token` 使用自製與 RFC 4231 對齊之 `HmacSha256::compute_hmac_response(secret, token)` 計算預期簽名，並採用位元 XOR 累加之常數時間比對演算法：
    ```rust
    let mut diff = 0u8;
    for (a, b) in signature.iter().zip(expected_sig.iter()) {
        diff |= a ^ b;
    }
    diff == 0
    ```
  - 徹底移除舊版 raw secret 明文/位元組直接比對邏輯。
  - 包含 RFC 2104 / RFC 4231 標準 Golden Vector 測試 `test_rfc2104_golden_vector`（`key="Jefe"`, `data="what do ya want for nothing?"` 產出 `5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843`）。

### 2. Purging of IPv4 TCP 127.0.0.1 Loopbacks
- **檔案**：`tests/e2e/framework/socket_harness.py` & `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
- **程式碼事實**：
  - `socket_harness.py`: 清除所有 `AF_INET` / `127.0.0.1` 迴路 socket fallback 與硬編碼連線邏輯，原生 socket 建立一律採用 `socket.AF_VSOCK` 與 `socket.AF_UNIX` (`/dev/socket/linux_bridge` 或 `/tmp/dev_socket/linux_bridge`)。
  - `LinuxPortalService.java` 與 `VsockPortalClient.java`: 清除所有 TCP 降級迴路，採用 Linux 原生 `AF_VSOCK` (socket family 40, `VSOCK_PORTAL_PORT` 5000) 進行 IPC 與 Hardware Portal 傳輸。

### 3. VsockFrameHeader 二進位標頭與結構化 Payload 串流
- **檔案**：`frameworks/base/services/core/java/com/android/server/linux/VsockPortalClient.java`, `LinuxPortalService.java` & `guest/bridge-agent/src/portal.rs`
- **程式碼事實**：
  - `VsockPortalClient.java` 實作 13 位元組 Big-Endian 網路字節序 `VSOK` Frame Header (Magic `0x56534F4B` = "VSOK", `frameType` `0x01`, `payloadLen`, `seqId`)。
  - `LinuxPortalService.java` 串流發送：
    - Camera frames: `sendVsockCameraFramePayload` (`0x43414D46` "CAMF" SubType + `width` + `height` + `NV21` format + timestamp + NV21 byte stream)。
    - Audio PCM: `sendVsockAudioPayload` (`0x4155444F` "AUDO" SubType + payloadLen + PCM byte stream)。
    - Location updates: `sendGeoClueLocationUpdate` (`0x47454F43` "GEOC" SubType + JSON string `{"Latitude":..., "Longitude":..., "Accuracy":...}`)。
  - `portal.rs`: 採用 Serde/JSON demux 動態接收與解析 Host 端事件，更新 `GLOBAL_PORTAL_STATE` 並處置 Guest RPC 請求 (`camera.request`, `camera.status`, `audio.request`, `audio.status`, `location.get`, `file.read`, `file.write`, `file.list`)。已徹底移除硬編碼 mock 座標 `(0.0, 0.0)` 與靜態 `"available"` 寫死回應。

### 4. Build & Test 執行驗證
- 執行 `python3 tests/e2e/runner.py`：
  - **430/430 測試全數 PASS** (Pass Rate: 100.0%, Duration: 39.35 seconds, Exit Code: 0)。
- 執行 `$HOME/.cargo/bin/cargo test` in `guest/bridge-agent`：
  - **34/34 單元測試全數 PASS** (Pass Rate: 100.0%, Duration: 10.00 seconds, Exit Code: 0)。

### 5. Integrity Violations & Adversarial Review
- 查核無任何硬編碼測試結果、Facade/Dummy 虛假實作、無邏輯繞過、造假報告或偽造認證標籤。

---

## 2. 推理鏈 (Logic Chain)

1. **認證安全性**：在 `auth.rs` 中使用 64 位元組 `AuthHandshakePayload` 與常數時間 HMAC-SHA256 比對，並通過 RFC 4231 測試向量驗證，能有效阻絕時序攻擊 (Timing Attack) 與 Replay 攻擊。
2. **通訊架構合規性**：徹底清除 IPv4 TCP 127.0.0.1 迴路，強制限定 AF_VSOCK 與 AF_UNIX，確保 Host 與 Guest 隔離邊界符妥。
3. **二進位 framing 與 Payload 結構**：13 位元組 `0x56534F4B` Vsock Header 與 CAMF/AUDO/GEOC 結構化串流經 Java 與 Rust 雙向對齊解析，確保留載數據與動態狀態無縫同步。
4. **真實性驗證**：雙測試套件 (`runner.py` 430 項、`cargo test` 34 項) 均於本機環境真實執行完畢且 100% 通過，Git status 亦確認無殘留預編譯二進位產物或造假 JSON 檔案。

---

## 3. 注意事項 (Caveats)

No caveats. 所有審查項目均經由源碼閱讀與獨立指令執行驗證通過。

---

## 4. 結論 (Conclusion)

**VERDICT: APPROVE**

Round 4 在安全性 HMAC 驗證、Socket Lifecycle Purging、AF_VSOCK 封包 Framing 與二進位 Payload 串流等面向均符合項目規範，且 E2E 及 Rust 單元測試 100% 通過。

---

## 5. 驗證方法 (Verification Method)

審查員獨立執行以下指令驗證：

1. **Rust 單元測試**：
   ```bash
   cd guest/bridge-agent && $HOME/.cargo/bin/cargo test
   # 輸出：test result: ok. 34 passed; 0 failed, Exit Code: 0
   ```

2. **E2E 測試套件**：
   ```bash
   python3 tests/e2e/runner.py
   # 輸出：TOTAL TESTS EXECUTED: 430, PASSED: 430, FAILED: 0, Exit Code: 0
   ```

3. **Git 狀態清理查核**：
   ```bash
   git status --porcelain
   # 輸出：無任何未追蹤之二進位檔或預編譯報告檔
   ```
