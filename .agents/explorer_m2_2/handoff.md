# Handoff Report: Explorer 2 (Milestone M2 - F-R2-002 & F-R2-003)

**Agent ID**: Explorer 2 (Replacement)  
**Milestone**: Milestone M2 (AVF Guest Setup & CE Storage Encryption)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_2`  
**Date**: 2026-08-06  

---

## 1. Observation (觀察事實)

1. **現有程式碼架構**：
   - 專案根目錄下的 C++ 守護行程位於 `system/linux_bridge/`，現有檔案包含 `main.cpp` (Line 1–70)、`socket_server.h/cpp`、`vsock_framing.h/cpp`、`vsock_server.h/cpp`、`hmac_auth.h/cpp`。
   - `system/linux_bridge/Android.bp` (Line 3–26) 目前編譯 `linux_bridge` 執行檔，連結 `liblog`, `libutils`, `libcutils`, `libbinder`, `libcrypto`。
   - 單元測試位於 `system/linux_bridge/tests/linux_bridge_test.cpp` (Line 1–177)，目前已測試 Socket Framing、Vsock Framing、Socket Server 傳輸生命週期。

2. **技術規範與藍圖對齊**：
   - `PROJECT.md` (Line 49–50) 定義 `F-R2-002` (4-Layer Storage Image Layout) 與 `F-R2-003` (LUKS2 CE Storage Encryption)。
   - `TEST_INFRA.md` (Line 87–200, Line 628–630) 定義了 `T1-31` 至 `T1-40`（Tier 1 功能覆蓋測試）以及 `T2-31` 至 `T2-40`（Tier 2 邊界測試）與 Pairwise 整合測試 `T3-PAIR-16` (`Debian VM` x `Storage Layout`) 和 `T3-PAIR-17` (`Storage Layout` x `LUKS Encryption`)。

3. **E2E 測試驗證邏輯**：
   - `tests/e2e/tier1_feature_coverage/test_m2_tier1.py` (Line 87–200) 驗證 `base_rootfs.img` 唯讀掛載於 `/`、OverlayFS 掛載於 `/etc`, `/var`, `/usr`、LUKS2 解密裝置 `/dev/mapper/user_home_decrypted` 掛載至 `/home/user`，以及 `vm_state.snapshot` 快照建立。
   - `tests/e2e/tier2_boundary_corner/test_m2_tier2.py` (Line 200–250) 驗證 CE key 衍生、解鎖裝載/鎖屏關閉、密文讀取防護與 key wipe 記憶體清空。

---

## 2. Logic Chain (推理鏈)

1. **Step 1 (基於 Observation 1 & 2)**：
   現有 `system/linux_bridge` 已具備基礎 vsock/socket 控制管道與 HMAC 驗證機制，但尚未包含專屬的磁碟圖層管理 (`StorageManager`) 與 LUKS2 加密管理 (`LuksCrypto`) 模組。

2. **Step 2 (基於 Observation 2 & 3)**：
   `F-R2-002` 需要管理 `/data/misc/linux/` 下的 4 層映像檔：`base_rootfs.img` (2500 MB, ro)、`custom_overlay.img` (4000 MB, rw OverlayFS)、`user_home.img` (5000 MB, LUKS2 encrypted)、`vm_state.snapshot`。宿主機端的 `StorageManager` 必須能自動建立稀疏檔案、進行空間檢查 (`statvfs`) 並組合 crosvm 磁碟參數 (`--disk`, `--rwdisk`)。

3. **Step 3 (基於 Observation 2 & 3)**：
   `F-R2-003` 要求將 `user_home.img` 與 Android CE (Credential Encrypted) 金鑰綁定。當 Android 使用者解鎖 (`onUserUnlocking`) 時，由 Keystore2 / KeyMint 衍生 HKDF 子金鑰並呼叫 `LuksCrypto::openLuks2Container()` 解密建立 `/dev/mapper/user_home_decrypted`；鎖屏時呼叫 `closeLuks2Container()` 並呼叫 `OPENSSL_cleanse()` 擦除記憶體中的金鑰，防止 Cold Boot 攻擊。

4. **Step 4 (基於 Step 1, 2, 3)**：
   Worker 在進行 Milestone M2 的 C++ 模組開發時，應在 `system/linux_bridge/src/` 中新增 `storage_manager.h/cpp` 與 `luks_crypto.h/cpp`，並於 `Android.bp` 中加入依賴項 `libcryptsetup` 與 `libcrypto`。

---

## 3. Caveats (注意事項與未覆蓋範圍)

1. **實體硬體與 SELinux 權限**：本分析報告著重於 C++ 邏輯介面與 E2E 模擬環境對齊。在真實 Android 裝置上部署時，`linux_bridge` Process 需要擁有讀寫 `/dev/mapper/` 與執行 `dm-crypt` ioctl 的 SELinux `linux_bridge.te` 權限。
2. **區塊裝置掛載耗時**：`cryptsetup` 格式化與掛載可能增加 100-200ms 開機延遲，宜於背景非同步 Thread 處理。

---

## 4. Conclusion (結論)

1. **設計完整度**：`F-R2-002` (4-Layer Storage Image Layout) 與 `F-R2-003` (LUKS2 CE Storage Encryption) 之技術架構、C++ 類別介面 (`StorageManager`, `LuksCrypto`)、金鑰生命週期與建置定義 (`Android.bp`) 已完成全面分析與設計。
2. **成果檔案**：詳細技術設計與程式碼藍圖已撰寫至 `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_2/analysis.md`。

---

## 5. Verification Method (驗證方法)

1. **查驗分析報告**：
   檢查 `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_2/analysis.md` 內容是否涵蓋 `StorageManager` 與 `LuksCrypto` 之 `.h`/`.cpp` 設計、`Android.bp` 配置及測試用例對照表。
2. **執行現有 E2E 測試驗證**：
   可在專案根目錄下執行：
   ```bash
   python3 tests/e2e/runner.py
   ```
   確認 `F-R2-002` (T1-31..35, T2-31..35) 與 `F-R2-003` (T1-36..40, T2-36..40) 測試項目符合預期。
