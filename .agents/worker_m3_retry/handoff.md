# Handoff Report — Milestone 3 Retry (M3 HMACSHA256 Constant Fix)

## 1. Observation (觀察事實)

### 1.1 `system/linux_bridge/hmac_auth.cpp` 密碼學常數修正
- **檔名與行號**：`system/linux_bridge/hmac_auth.cpp` 第 87 行
- **修正前程式碼**：
  ```cpp
  static const uint32_t K[64] = {
      ...
      0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef4a3f7, 0xc67178f2
  };
  ```
- **修正後程式碼**：
  ```cpp
  static const uint32_t K[64] = {
      ...
      0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  };
  ```
- **變更說明**：將 `K[62]` 之常數從錯字 `0xbef4a3f7` 修正為 FIPS 180-4 / RFC 4231 標準之 `0xbef9a3f7`。

### 1.2 C++ 單元測試與 RFC 4231 測試結果
- **測試指令 1**：
  `clang++ -std=c++20 -DHAS_OPENSSL=0 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test && ./build_out/bin/linux_bridge_test`
- **測試結果 1**：
  ```
  PASS (50/50 succeeded)
  [TEST] Socket Server Teardown Shutdown Handling... 
  PASS
  ===================================================
  NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY
  ```
- **測試指令 2**：
  `clang++ -std=c++20 -DHAS_OPENSSL=0 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/challenger_m3_2_empirical_test.cpp -o build_out/bin/challenger_m3_2_empirical_test && ./build_out/bin/challenger_m3_2_empirical_test`
- **測試結果 2**：
  ```
  === Empirical Challenger M3_2: Native C++ Stress & Protocol Test Suite ===
  [EMPIRICAL M3_2 TEST 1] RFC 4231 Test Case 2 Golden Vector Verification... PASS
  [EMPIRICAL M3_2 TEST 2] HMAC Handshake Verification & Edge Case Matrix... PASS
  [EMPIRICAL M3_2 TEST 3] VsockServer Guest CID Security Filter... PASS
  [EMPIRICAL M3_2 TEST 4] SocketServer + VsockServer State Integration... PASS
  ==========================================================================
  NATIVE EMPIRICAL TEST RESULT: ALL TESTS PASSED SUCCESSFULLY
  ```

### 1.3 Rust ARM64 Agent 編譯檢查結果
- **測試指令**：`$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu` 在 `guest/bridge-agent` 目錄執行。
- **測試結果**：
  ```
  Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.07s
  ```
  Exit code `0`，0 warnings，0 errors。
- 另外在 `guest/portal-agent` 目錄下執行 `$HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu` 亦同為 Exit code `0`，0 warnings，0 errors。

---

## 2. Logic Chain (邏輯推理鏈)

1. **密碼學常數對齊**：
   - 觀察：RFC 4231 Golden Vector Test 2 使用 `Key = "Jefe"` 與 `Data = "what do ya want for nothing?"`。
   - 推理：修正前 C++ internal SHA-256 fallback 計算出的 Hex 為 `5b2bbba31425a1de386e4538bd66a8d52ddb3c9372b7a741792bd5568a79f30f`，主因是 `K[62]` 常數中第 4 位 bit 錯誤（`0xbef4a3f7` vs `0xbef9a3f7`）。
   - 結論：將 `K[62]` 修正為 `0xbef9a3f7` 後，計算結果精確符合標準黃金向量 `5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843`。

2. **跨語言 HMAC-SHA256 協議相容性**：
   - 觀察：Guest 端的 Rust Agent (`guest/bridge-agent/src/auth.rs`) 採用標準 RFC 4231 HMAC-SHA256 計算，Host 端 C++ 在 fallback 模式下修改後正確輸出與 Rust 端完全相同的 HMAC 簽名。
   - 推理：在握手階段，Host 與 Guest 將以相同 32 位元組 secret 計算與驗證 HMAC 簽名，不再出現 `HMAC signature mismatch` 的安全性誤報。
   - 結論：C++ 單元測試 `linux_bridge_test` 與實證測試 `challenger_m3_2_empirical_test` 均 100% 通過；Rust Agent 通過 `aarch64-unknown-linux-gnu` 檢查。

---

## 3. Caveats (注意事項與限制)

- 無 caveats (No caveats)。所有修改與測試結果皆已進行實測驗證。

---

## 4. Conclusion (評估結論)

- Milestone 3 Retry 的所有修復目標已完全達成：
  1. `system/linux_bridge/hmac_auth.cpp` 第 87 行之 SHA-256 常數 `K[62]` 已修正為 `0xbef9a3f7`。
  2. C++ 測試套件 `linux_bridge_test` 及 `challenger_m3_2_empirical_test`（包含 RFC 4231 金鑰驗證測試）全數通過 (100% PASS)。
  3. Rust `guest/bridge-agent` 及 `guest/portal-agent` 通過 `aarch64-unknown-linux-gnu` 交叉編譯檢查（0 warnings, 0 errors）。

---

## 5. Verification Method (獨立驗證步驟)

請在專案根目錄 `/Users/iml1s/Documents/mine/aosp-linux` 執行以下指令驗證：

1. **C++ 單元測試與 RFC 4231 黃金向量驗證**：
   ```bash
   mkdir -p build_out/bin
   clang++ -std=c++20 -DHAS_OPENSSL=0 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/linux_bridge_test.cpp -o build_out/bin/linux_bridge_test
   ./build_out/bin/linux_bridge_test

   clang++ -std=c++20 -DHAS_OPENSSL=0 -Wall -Wextra -pthread -I. system/linux_bridge/hmac_auth.cpp system/linux_bridge/vsock_framing.cpp system/linux_bridge/socket_server.cpp system/linux_bridge/vsock_server.cpp tests/unit/challenger_m3_2_empirical_test.cpp -o build_out/bin/challenger_m3_2_empirical_test
   ./build_out/bin/challenger_m3_2_empirical_test
   ```
   *預期結果*：`NATIVE TEST RESULT: ALL TESTS PASSED SUCCESSFULLY` 並且 `[EMPIRICAL M3_2 TEST 1] RFC 4231 Test Case 2 Golden Vector Verification... PASS`。

2. **Rust Agent 交叉編譯檢查**：
   ```bash
   (cd guest/bridge-agent && $HOME/.cargo/bin/cargo check --target aarch64-unknown-linux-gnu)
   ```
   *預期結果*：Exit code 0, 0 warnings, 0 errors.
