# 第二十一章：安全模型與 SELinux

## 21.1 威脅模型

### 攻擊者類型與對應防禦

| 攻擊者 | 主要威脅 | 防禦 |
|--------|---------|------|
| 惡意 Android App | 存取 LinuxManagerService 未授權操作 | signature/privileged permission |
| 惡意 Linux App（非 root）| 透過 portal 獲取不該有的權限 | AppOps + 使用者確認 |
| Guest root（UID 0）| 逃脫 VM 邊界，存取 Host | KVM 頁表隔離，crosvm seccomp |
| 被入侵的 Guest systemd | 啟動未授權服務，耗盡資源 | cgroup 配額 |
| 惡意 Debian 套件 | 安裝 rootkit（在 Guest 中）| Guest 內部為使用者自負，不影響 Host |
| 惡意 terminal escape sequence | OSC 注入、XSS-like 攻擊 | ANSI parser 嚴格過濾，OSC 白名單 |
| 惡意 shared file | path traversal，symlink attack | virtiofs path restriction |
| vsock spoofing | 假裝是合法 Guest | HMAC-SHA256 + nonce（vsock CID 本身提供部分隔離）|
| RPC parser bug | Buffer overflow in linux_bridge | Rust memory safety + seccomp + fuzzing |
| 遭竄改 Guest image | 載入惡意 kernel/rootfs | AVB 2.0 RSA-4096 簽章驗證 |
| Rollback attack | 降版到有漏洞的 image | rollback index（avb_rollback_index）|
| Local physical attacker | 取得已加密的 user_home.img | LUKS2 + CE key binding（PIN 保護）|
| Multi-user cross-user | 使用者 A 存取使用者 B 的 Linux data | per-user CE key 隔離 |

### Guest Escape 防禦層次

```
Layer 1: KVM page table isolation（硬體）
Layer 2: crosvm process（SELinux: crosvm.te，seccomp）
Layer 3: crosvm 本身的 sandbox（limited syscalls）
Layer 4: linux_bridge authentication（拒絕未認證 RPC）
Layer 5: LinuxPortalService AppOps（拒絕未授權 Portal）
Layer 6: virtiofs path restriction（拒絕越界路徑）
```

## 21.2 SELinux Domain 設計

### linux_manager.te（NEW）

```
# LinuxManagerService 在 system_server 內的部分
# 不需要獨立 domain，繼承 system_server，但有 restricted 標記

type linux_manager, domain;
type linux_manager_exec, exec_type, file_type, system_file_type;

# 允許的操作
allow linux_manager linux_data_file:dir { read write create };
allow linux_manager vm_config_file:file { read write create unlink };
allow linux_manager linux_image_file:file { read open };

# 禁止的操作（neverallow）
neverallow linux_manager radio_data_file:file { read write };
neverallow linux_manager efs_file:file { read write };
neverallow linux_manager keystore_data_file:file { read write };
```

### linux_bridge.te（NEW）

```
type linux_bridge, domain;
type linux_bridge_exec, exec_type, file_type, system_file_type;

# 繼承一般 domain 限制
typeattribute linux_bridge coredomain;

# 允許 vsock
allow linux_bridge self:vsock_socket { create connect accept read write };
allow linux_bridge virtmgr:binder call;  # 查詢 VM CID

# 不允許
neverallow linux_bridge android_data_file:dir { read write };
neverallow linux_bridge radio_service:service_manager find;
neverallow linux_bridge su:process transition;

# seccomp policy（限制 syscalls）
allow linux_bridge self:security setenforce;  # NOT allowed

# 允許與 system_server 通訊
allow linux_bridge system_server:unix_stream_socket { connectto read write };
```

### linux_portal.te（NEW）

```
type linux_portal, domain;

# Portal service 需要存取 camera, mic, location service
allow linux_portal camera_service:binder call;
allow linux_portal audio_service:binder call;
allow linux_portal location_service:binder call;

# 但必須先通過 AppOps 檢查
# 不允許直接存取硬體設備
neverallow linux_portal camera_device:chr_file { read write };
neverallow linux_portal audio_device:chr_file { read write };
```

## 21.3 SELinux NEVERALLOW 清單

這些是正式版本必須存在的 neverallow：

```
# 1. Linux VM domain 不可存取 Android 私有資料
neverallow { linux_bridge linux_portal linux_terminal } {
    privapp_data_file system_app_data_file platform_app_data_file
}:dir { read write };

# 2. 不可存取 radio/telephony 相關
neverallow linux_bridge { radio_data_file radio_service }:* *;

# 3. 不可存取加密儲存根目錄
neverallow linux_vm_domain efs_file:* *;

# 4. 不可轉移到特權進程
neverallow linux_vm_domain { su init kernel }:process transition;

# 5. 不可開啟任意 block device
neverallow linux_bridge block_device:blk_file { read write open ioctl };

# 6. 不可存取 KeyMint 原始資料
neverallow linux_vm_domain keystore_data_file:file *;

# 7. crosvm 不可存取 Android binder service 列表
neverallow crosvm servicemanager:binder { call transfer };
```

## 21.4 新增服務的安全屬性表

| 服務 | UID | SELinux Domain | Binder 服務名稱 | 所需 Signature Perm | seccomp | crash 隔離 |
|------|-----|---------------|----------------|---------------------|---------|-----------|
| LinuxManagerService | 1000 (system) | system_server | linux | MANAGE_LINUX_ENVIRONMENT | 繼承 system_server | 影響 system_server |
| linux_bridge daemon | linux_bridge (新) | linux_bridge | N/A（unix socket）| N/A | 嚴格 | 獨立進程，可自動重啟 |
| LinuxPortalService | 1000 (system) | system_server | linux_portal | LINUX_PORTAL_ACCESS | 繼承 system_server | 影響 system_server |
| LinuxTerminal App | app (priv-app) | linux_terminal | N/A | USE_LINUX_TERMINAL | N/A | 獨立 App |
| GuestUpdateAgent | linux_update | linux_update_service | N/A | 內部 | 嚴格 | 獨立進程 |

## 21.5 crosvm seccomp

crosvm 已有自己的 seccomp filter（EXISTING），本專案需確認並補充：

```
必須允許的 syscalls（crosvm）：
open, read, write, close, mmap, ioctl (KVM specific)
socket (AF_VSOCK), bind, accept, connect, send, recv
futex, nanosleep, clock_gettime, prctl, sigprocmask

必須禁止（額外添加）：
ptrace → block（防止 Guest trace Host）
reboot → block
pivot_root → block
```

---

# 第二十二章：OTA、Guest Update 與 Rollback

## 22.1 Host AOSP OTA 與 Guest OTA 分離

```
原則：
- Host AOSP OTA（Android 版本更新）和 Guest Linux OTA 獨立管理
- Host OTA 不刪除 user_home.img
- Host OTA 後，如果 Bridge 協議版本不相容，觸發 Guest update
- Guest OTA 失敗不影響 Host 系統

時序：
1. Host AOSP OTA 下載完成
2. OTA pre-install：LinuxManagerService 備份 vm_metadata
3. Host OTA 重啟應用
4. 新 Host 啟動：檢查 Guest bridge 版本相容性
5. 如不相容：強制觸發 Guest base image update
```

## 22.2 Guest Base Image A/B Update

```
儲存佈局：
base_a.img   (active slot)
base_b.img   (staging slot for next update)
vm_metadata  (active_slot: "a", boot_count: 0, rollback_index: N)

Update 流程：
1. update-agent 接收 UPDATE_AVAILABLE RPC（from Host LinuxImageManager）
2. 下載新 base image 到暫存 → staging/base_new.img
3. 驗證 AVB 2.0 RSA-4096 簽章
4. 驗證 rollback index（不允許降版：new_index >= vm_metadata.rollback_index）
5. atomic copy staging/base_new.img → base_b.img（或直接 dd）
6. 更新 vm_metadata：pending_slot="b", boot_count=0
7. Host LinuxManagerService 收到 UPDATE_STAGED 通知
8. 重啟 VM（或等待下次 VM 重啟）
9. Guest 啟動時：使用 pending_slot=b
10. 啟動成功：vm_metadata active_slot="b", boot_count++ (若 > 3 次則 rollback)
11. 標記成功：host 收到 UPDATE_SUCCESS

Rollback 觸發條件：
- boot_count > 3 → 自動 rollback 到 active_slot="a"
- 簽章驗證失敗 → 拒絕更新，保持 active_slot="a"
- Bridge 握手失敗 → 報告 DEGRADED，等待人工介入
```

## 22.3 APT 套件更新

```
套件更新策略：
- apt 更新只影響 overlay_rw.img（可寫層）
- base.img 保持乾淨（唯讀，可回滾）
- 重大套件更新（kernel, libc, systemd）需要 base image OTA

執行時機：
- 使用者手動觸發：Settings → Linux → Check for Updates
- 自動更新：充電中 + Wi-Fi（可設定）
- 不在 Doze 期間更新

apt 更新流程（Guest 端）：
1. android-bridge-agent 接收 APT_UPDATE RPC
2. 執行 apt-get update（更新 metadata）
3. 執行 apt-get upgrade --simulate（預覽）
4. 回報可用更新列表到 Host
5. Host 顯示通知：「Linux: 5 packages available」
6. 使用者確認 → 執行 apt-get upgrade

注意：kernel 更新需要重啟 VM，不會自動 reboot
```

## 22.4 Factory Reset 行為

```
Android Factory Reset：
1. /data 格式化
2. user_home.img（在 /data/user/0/...）被刪除
3. CE key 被清除
4. base_a.img、base_b.img、overlay_rw.img 全部消失
5. 效果：Linux 環境完全清除
6. 下次啟動：重新安裝（InstallerService）

僅重置 Linux（不重置 Android）：
API：linuxManager.resetLinuxEnvironment(keepHome=false)
1. 停止 VM
2. 刪除 overlay_rw.img（重建新的）
3. 刪除 user_home.img（可選：keepHome=true 保留）
4. 保留 base_a.img（基底不變）
5. 重新建立 overlay_rw.img
6. 如果 keepHome=true：重新加密 user_home.img（新 LUKS key）

Backup / Restore：
- Export：base_a.img（不含）+ overlay_rw.img + user_home.img → tar.gz（加密）
- Import：解包，驗證簽章，恢復
- Google Backup：不預設備份（含隱私資料）
- 本地備份：可由使用者觸發
```

## 22.5 Bridge API 相容性矩陣

```
版本矩陣：
Host Bridge v1.0：支援 Guest Bridge v1.0
Host Bridge v1.1：支援 Guest Bridge v1.0, v1.1
Host Bridge v2.0：僅支援 Guest Bridge v2.0+

當 Host 升級但 Guest 未升級：
1. HELLO 版本協商失敗
2. LinuxManagerService 狀態 → DEGRADED
3. 顯示通知："Linux environment update required"
4. 觸發 Guest base image update（強制）
5. Update 完成前，Terminal 和 App 不可用
6. 保留 user_home.img（用戶資料不丟失）

Rollback 後的相容性：
- Guest rollback 到舊版本
- Host Bridge 需要向下相容舊版本協議（至少 -1 major）
- 否則 Host 也需要相應降版（不應發生在生產環境）
```
