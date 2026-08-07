# Handoff Report: Milestone M2 Iteration 3 Remediation Analysis & Design

**Role**: Explorer 1 (`teamwork_preview_explorer`)  
**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i3_1`  
**Workspace Root**: `/Users/iml1s/Documents/mine/aosp-linux`  
**Milestone**: M2 (AVF Guest Setup & CE Storage Encryption - Iteration 3)  
**Target Files**:
- `guest/scripts/launch_vm.sh`
- `guest/scripts/init_storage_layout.sh`
- `guest/scripts/guest_mount_overlay.sh`
- `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`
**Analysis Document**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i3_1/analysis.md`

---

## 1. Observation (觀察事實)

直接觀察到的實驗證據、檔案路徑、行號與程式碼缺陷：

1. **`guest/scripts/launch_vm.sh` 重導向語法致 0-Byte 清空**:
   - 行號 31 & 36：`exec 200>"$BASE_IMG"` 與 `exec 201>"$OVERLAY_IMG"`。
   - 使用 `>` (寫入重導向) 觸發 POSIX `O_WRONLY | O_CREAT | O_TRUNC`。
   - 啟動 VM 時，2,621,440,000 位元組 (2500MB) 的 `base_rootfs.img` 與 4,194,304,000 位元組 (4000MB) 的 `custom_overlay.img` 被立即強制抹除為 0 位元組。
   - 硬編碼問題：第 5 行傳入 `$CONFIG_FILE` 後未進行任何 JSON 解析，硬編碼 CPU=4, RAM=4096MB, CID=3 及預設路徑。

2. **`guest/scripts/init_storage_layout.sh` 零位元組修復跳過**:
   - 行號 12, 22, 32, 41：`if [ ! -f "${BASE_IMG}" ]; then`。
   - `[ ! -f ]` 僅判斷檔案是否存在。當映像檔長度為 0 位元組時，條件為 false，無法觸發 `truncate -s 2500M` / `4000M` 進行重新初始化。

3. **`guest/scripts/guest_mount_overlay.sh` 缺乏復原與 ENOSPC 處置**:
   - 行號 27-29：`mount -t overlay ... || echo "Warning: OverlayFS mount for /$dir skipped or failed"`。
   - 缺少 `/mnt/overlay` 剩餘空間預檢，掛載失敗時無 upperdir/workdir 清理重試機制，亦無唯讀 bind mount 回退。

4. **`tests/e2e/tier2_boundary_corner/test_m2_tier2.py` 假象斷言**:
   - 行號 114, 127, 154：`CustomAssertions.assert_in("overlay", content)`, `assert_in("custom_overlay.img", content)`, `assert_in("flock", content)`。
   - 測試僅比對檔案內字串，未真實執行子程序或檢驗映像檔長度與鎖定狀態。

---

## 2. Logic Chain (推理鏈)

1. **`launch_vm.sh` 0-Byte 清空推理**:
   - `flock` 操作僅需要作業系統開啟該檔案取得描述符（FD），與 Open Mode 無關。使用唯讀模式 `<` 開啟 FD (例如 `exec 200<"$BASE_IMG"`) 不會帶入 `O_TRUNC` 標誌，即可兼顧多處理機檔案鎖定與檔案完整性保護。
   - 透過內聯 Python3 解析 `vm_config.json`，可將 `VM_CPUS`, `VM_MEM`, `VM_CID`, `VM_KERNEL`, `VM_INITRD` 及磁碟路徑動態注入 crosvm 參數中，徹底消除硬編碼。

2. **`init_storage_layout.sh` 復原推理**:
   - 將判斷式升級為 `if [ ! -f "$IMG" ] || [ ! -s "$IMG" ]; then`。`-s` 測試檔案長度是否大於 0。當檔案不存在 OR 長度為 0 時，皆會觸發 `truncate` 與 `mkfs` 重新初始化，自動修復遭受損毀的映像檔。

3. **`guest_mount_overlay.sh` 空間與掛載復原推理**:
   - 掛載前透過 `df -k /mnt/overlay` 預檢剩餘空間，若低於閾值則清除 workdir 快取。
   - 若 `mount -t overlay` 失敗，自動 purge `upperdir` 與 `workdir` 後重試掛載。若仍失敗，降級為 `mount --bind -o ro /mnt/lower/$dir /$dir`，確保 Guest OS 基礎檔系統可用。

4. **Tier 2 E2E 測試實證化推理**:
   - T2-32: 驗證 `guest_mount_overlay.sh` 腳本語法與復原邏輯關鍵字。
   - T2-33: 在 Python 測試中於 `tempdir` 建立 0 位元組 `base_rootfs.img` 與 `custom_overlay.img`，執行 `init_storage_layout.sh` 後實證斷言檔案長度恢復為 2.5GB 與 4GB。
   - T2-35: 於 `tempdir` 初始化映像檔，執行 `launch_vm.sh` 後實證斷言 `os.path.getsize()` 未被清空為 0 位元組。

---

## 3. Caveats (保留事項)

- **無保留事項 (No caveats)**：所有診斷與擬修復之 Bash / Python 代碼均已進行語法與邏輯驗證，分析報告 `analysis.md` 已完整記載。

---

## 4. Conclusion & Action Items (結論與修復建議)

Explorer 1 已完成對 4 個缺陷的鑑識分析並設計了完整修復方案。

**Actionable Requirements for Implementer**:
1. 修改 `guest/scripts/launch_vm.sh`:
   - 將 `exec 200>"$BASE_IMG"` 與 `exec 201>"$OVERLAY_IMG"` 替換為 `exec 200<"$BASE_IMG"` 與 `exec 201<"$OVERLAY_IMG"`。
   - 加入 Python inline 動態解析 `$CONFIG_FILE` JSON 設定檔邏輯。
2. 修改 `guest/scripts/init_storage_layout.sh`:
   - 將 `[ ! -f ]` 修改為 `[ ! -f ] || [ ! -s ]`。
3. 修改 `guest/scripts/guest_mount_overlay.sh`:
   - 加入 ENOSPC 預檢、upperdir/workdir purge 重試與 lowerdir 唯讀 bind mount 回退。
4. 修改 `tests/e2e/tier2_boundary_corner/test_m2_tier2.py`:
   - 將 T2-32, T2-33, T2-35 替換為實證子程序執行與檔案大小/鎖定狀態檢查測試。

詳細程式碼變更參見 `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i3_1/analysis.md`。

---

## 5. Verification Method (獨立驗證方式)

Implementer 完成修復後，可按以下步驟驗證：

1. **驗證修復後的 `launch_vm.sh` 不再清空檔案**:
   ```bash
   TEST_DIR="/tmp/test_launch_fix"
   mkdir -p "$TEST_DIR"
   bash guest/scripts/init_storage_layout.sh "$TEST_DIR"
   SIZE_BEFORE=$(stat -f%z "$TEST_DIR/base_rootfs.img" 2>/dev/null || stat -c%s "$TEST_DIR/base_rootfs.img")
   
   # 建立測試 vm_config.json
   cat <<EOF > "$TEST_DIR/test_cfg.json"
   {
     "cpu": {"cpus": 2},
     "memory": {"ram_mb": 2048},
     "vsock": {"cid": 4},
     "disks": {
       "base_rootfs": {"path": "$TEST_DIR/base_rootfs.img"},
       "custom_overlay": {"path": "$TEST_DIR/custom_overlay.img"},
       "user_home": {"path": "$TEST_DIR/user_home.img"}
     }
   }
   EOF
   
   bash guest/scripts/launch_vm.sh "$TEST_DIR/test_cfg.json"
   SIZE_AFTER=$(stat -f%z "$TEST_DIR/base_rootfs.img" 2>/dev/null || stat -c%s "$TEST_DIR/base_rootfs.img")
   echo "Size before: $SIZE_BEFORE, Size after: $SIZE_AFTER"
   # 應顯示兩者長度一致 (2621440000 bytes)！
   rm -rf "$TEST_DIR"
   ```

2. **驗證修復後的 `init_storage_layout.sh` 能修復 0-byte 檔案**:
   ```bash
   TEST_DIR="/tmp/test_init_fix"
   mkdir -p "$TEST_DIR"
   touch "$TEST_DIR/base_rootfs.img" # 0-byte 檔案
   bash guest/scripts/init_storage_layout.sh "$TEST_DIR"
   ls -l "$TEST_DIR/base_rootfs.img" # 應顯示 2621440000 bytes！
   rm -rf "$TEST_DIR"
   ```

3. **執行完整 E2E 測試**:
   ```bash
   python3 tests/e2e/runner.py
   ```
