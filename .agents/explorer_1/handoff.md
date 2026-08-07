# Handoff Report — explorer_1

---

## 1. Observation (直接觀察)

1. **Original Request File**: `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
   - 要求明確定義 R1 (AOSP Framework & Core Modification Architecture: `LinuxManagerService`, AIDL, `SystemServer` 整合) 與 R2 (AVF / crosvm / KVM Non-Protected Debian ARM64 Guest Setup & Storage Encryption)。
2. **Technical Plan Artifact**: `/Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md`
   - 第 3 節指出 AOSP 現有 Virtualization 模組位於 `packages/modules/Virtualization/`。
   - 第 6 節定義了 AOSP 原始碼修改地圖 (`frameworks/base/core/java/android/system/linux/`, `frameworks/base/services/core/java/com/android/server/linux/`, `SystemServer.java`)。
   - 第 7 節與 7.2 節提供 `ILinuxManager.aidl` 介面及 SystemServer `startOtherServices()` 整合機制。
   - 第 8 節與 8.1 節定義 `/data/system/linux/` 映像檔結構 (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`) 與 LUKS CE 加密綁定機制。
   - 第 9 節與 9.1 節定義 virtio-vsock 埠號分配 (Port 5000 Control, Port 5001 PTY Stream, Port 5002 Wayland) 與 HMAC-SHA256 雙向認證握手。
3. **Agent State Files**:
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/DISPATCH.md`
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/BRIEFING.md`
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/progress.md`
   - `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/analysis.md`

---

## 2. Logic Chain (推理鏈)

1. **Premise 1 (From Observation 1 & 2)**: 專案目標為打造雙 OS 融合系統，要求強安全隔離同時提供自由安裝 GNU/Linux 套件的能力與流暢 UI。
2. **Premise 2 (R1 Architecture Logic)**:
   - 若直接在 `system_server` 內部處理網路/vsock 解析，封包異常將導致整台 Android 設備重啟（`system_server` crash loop）。
   - 推理結論：必須採取 `LinuxManagerService` (SystemServer Java API & State Machine) + `linux_bridge` (獨立 C++/Rust Daemon Process) 隔離架構，隔絕崩潰風險。
3. **Premise 3 (R2 Hypervisor & Encryption Logic)**:
   - Protected Microdroid VM 採用唯讀與記憶體加密，無法滿足 APT 套件安裝與 dma-buf Wayland 視窗共享需求。因此必須採用 Non-Protected VM 模式。
   - 為補足 Non-Protected 模式下的資料實體安全，必須對存放個人資料的 `user_home.img` 套用 LUKS2 加密，且 Passphrase 必須與 Android 使用者憑證加密 (CE) 金鑰動態繫結，達成解鎖即掛載、鎖屏即保護的密鑰生命週期。
4. **Premise 4 (Vsock Handshake Security Logic)**:
   - vsock 通道雖隔離於外部 IP 網路，但需防範 Guest 內部未授權 Process 偽裝 Host 存取控制介面。
   - 推理結論：啟動時由 Host 產生單次 256-bit Nonce 經由 boot parameter 注入 Guest，Guest 經由 `android-bridge-agent` 以 HMAC-SHA256 算出的簽章進行 Vsock Port 5000 認證握手，通過後方開啟數據流埠號 (5001/5002)。

---

## 3. Caveats (注意事項與未調查領域)

1. **未實機編譯驗證**: 目前為架構研析與介面規範設計階段，尚未在實體 ARM64 裝置 (如 Qualcomm Snapdragon 8 Gen 2/3) 上執行 `m` 命令或編譯 AOSP images。
2. **SOC 廠商與 Hypervisor 差異**: 不同晶片廠商之內核 `/dev/kvm` 節點權限與 virtio-gpu 硬體加速驅動在各廠商 BSP 中可能存在相容性細節，需於後續硬體調試階段實測。
3. **無其餘 Caveats**: R1 與 R2 之介面、系統服務整合、儲存分區架構與 vsock 安全協定已完整定義於 `analysis.md` 中。

---

## 4. Conclusion (最終結論)

1. 完成了 R1 (AOSP Framework Architecture & LinuxManagerService) 之完整設計：
   - 制定 `android.system.linux` API 命名空間與完整 AIDL 介面 (`ILinuxManager.aidl`, `ILinuxStatusCallback.aidl`, `ILinuxTerminalCallback.aidl`)。
   - 設計 `com.android.server.linux` 系統服務層 (`LinuxManagerService`, `LinuxBridgeService`, `LinuxPortalService`) 與 `SystemServer.java` 整合點。
   - 建立了 `linux_bridge` 獨立 daemon 隔離機制，確保 `system_server` 防禦 vsock 解析崩潰。
2. 完成了 R2 (AVF / crosvm / KVM Guest Setup & LUKS Storage Encryption) 之完整設計：
   - 選定 Non-Protected crosvm VM 模式與 Debian 12 ARM64 發行版。
   - 設計 4 層式儲存架構 (`base_rootfs.img`, `custom_overlay.img`, `user_home.img`, `vm_state.snapshot`)。
   - 建立與 Android CE Storage Key 繫結的 LUKS2 儲存加密機制。
   - 制定 3 埠 (5000 Control, 5001 PTY, 5002 Wayland) virtio-vsock 傳輸與 HMAC-SHA256 雙向認證握手協定。

---

## 5. Verification Method (獨立驗證方法)

1. **檔案存在與內容檢驗**:
   - 檢查 `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/analysis.md` 包含完整 R1 (AIDL, SystemServer, LinuxManagerService) 與 R2 (AVF, Debian 12, LUKS CE, Vsock HMAC) 分析。
   - 檢查 `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/handoff.md` 包含完整的 5 大區塊 (Observation, Logic Chain, Caveats, Conclusion, Verification Method)。
2. **驗證命令**:
   ```bash
   cat /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/analysis.md | head -n 30
   cat /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_1/handoff.md | head -n 30
   ```
3. **無效化條件 (Invalidation Conditions)**:
   - 若 `analysis.md` 遺漏 `ILinuxManager.aidl` 定義、`SystemServer` 整合說明、或 LUKS CE 加密生命週期細節，則報告無效。
