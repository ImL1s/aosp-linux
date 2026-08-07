# Milestone M2 技術分析報告：4 層式儲存架構 (F-R2-002) 與 LUKS2 CE 儲存區加密 (F-R2-003)

**分析員**：Explorer 2 (Replacement)  
**日期**：2026-08-06  
**專案**：AOSP Dual-OS System (Milestone M2)  
**工作目錄**：`/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_2`  

---

## 1. 執行摘要 (Executive Summary)

本報告針對 AOSP Dual-OS 專案 Milestone M2 中兩項核心儲存與資安功能進行深入架構調查與 C++ 介面設計：
1. **F-R2-002 (4-Layer Storage Image Layout)**：位於 `/data/misc/linux/` (或 `/data/system/linux/`) 之 4 層虛擬磁碟映像檔架構（`base_rootfs.img` 唯讀層、`custom_overlay.img` 可寫 OverlayFS 層、`user_home.img` 個人目錄加密層、`vm_state.snapshot` 快照層）與 Guest 端 OverlayFS 組合掛載機制。
2. **F-R2-003 (LUKS2 CE Storage Encryption)**：結合 Android 憑證加密 (Credential Encrypted, CE) 金鑰與 Linux `dm-crypt` / `libcryptsetup` API，針對 `user_home.img` 進行 LUKS2 (`aes-xts-plain64`) 加密格式化、解密映射 (`/dev/mapper/user_home_decrypted`)、鎖屏金鑰記憶體擦除 (Key Wiping) 與防護機制。

---

## 2. Feature F-R2-002：4 層式儲存映像檔架構 (4-Layer Storage Image Layout)

### 2.1 架構規格與層級分層 (Layer Specification)

系統於宿主機 `/data/misc/linux/` 目錄下維護 4 個獨立檔名的映像檔：

| 層級 (Layer) | 映像檔名稱 | 預設容量 | 檔案系統/格式 | 宿主/Guest 掛載模式 | Guest 掛載點 | 說明與設計目的 |
|---|---|---|---|---|---|---|
| **Layer 1** | `base_rootfs.img` | 2500 MB | EXT4 / EROFS | Read-Only (`ro`) | `/` (根目錄) | 唯讀基礎 Debian 12 ARM64 系統映像檔。防止 Guest 被惡意篡改，支援 A/B OTA 降級與復原。 |
| **Layer 2** | `custom_overlay.img` | 4000 MB | EXT4 (Upper/Work) | Read-Write (`rw`) | `/etc`, `/var`, `/usr` (OverlayFS) | 系統設定變更與套件安裝 (`apt install`) 寫入層。透過 OverlayFS 覆蓋於 Layer 1 之上，重試/重置時可單獨清空。 |
| **Layer 3** | `user_home.img` | 5000 MB | LUKS2 (`aes-xts-plain64`) + EXT4 | Read-Write (`rw`) via dm-crypt | `/home/user` (`/dev/vdc`) | 使用者個人主目錄。綁定 Android CE Keymaster/KeyMint 金鑰，鎖屏時保護個人隱私資料。 |
| **Layer 4** | `vm_state.snapshot` | 可變 (Sparse) | crosvm raw binary snapshot | Read-Write (`rw`) | N/A (crosvm control) | crosvm CPU/RAM/Device 虛擬機快照檔。用於實現快速開機與 Suspend-Resume 狀態保存。 |

### 2.2 宿主端 `StorageManager` C++ 介面設計 (`system/linux_bridge/src/storage_manager.h`)

`StorageManager` 負責管理映像檔目錄建立、稀疏檔案 (Sparse File) 初始化、空間檢查 (`statvfs`)、完整性校驗與 crosvm 啟動參數建構。

```cpp
/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * StorageManager: 4-Layer Storage Image Layout Manager for AOSP Dual-OS
 */

#ifndef LINUX_BRIDGE_STORAGE_MANAGER_H
#define LINUX_BRIDGE_STORAGE_MANAGER_H

#include <string>
#include <vector>
#include <cstdint>

namespace android {
namespace system {
namespace linux_bridge {

struct StorageLayoutInfo {
    std::string baseRootfsPath;      // /data/misc/linux/base_rootfs.img
    std::string customOverlayPath;   // /data/misc/linux/custom_overlay.img
    std::string userHomePath;        // /data/misc/linux/user_home.img
    std::string vmStateSnapshotPath; // /data/misc/linux/vm_state.snapshot
    std::string decryptedMapperPath; // /dev/mapper/user_home_decrypted
    bool isInitialized;
};

class StorageManager {
public:
    static constexpr const char* DEFAULT_STORAGE_DIR = "/data/misc/linux";
    static constexpr uint64_t BASE_ROOTFS_SIZE_MB = 2500;
    static constexpr uint64_t CUSTOM_OVERLAY_SIZE_MB = 4000;
    static constexpr uint64_t USER_HOME_SIZE_MB = 5000;

    explicit StorageManager(std::string baseDir = DEFAULT_STORAGE_DIR);
    ~StorageManager() = default;

    // 初始化與建立 4 層映像檔
    bool initializeLayout();

    // 建立指定大小之稀疏檔案 (ftruncate / fallocate)
    bool createSparseImage(const std::string& path, uint64_t sizeMb);

    // 檢查硬碟剩餘空間 (避免 ENOSPC 空間爆滿)
    bool checkAvailableSpace(uint64_t requiredMb) const;

    // 驗證 4 層映像檔完整性與檔頭
    bool verifyStorageIntegrity();

    // 取得當前儲存佈局資訊
    StorageLayoutInfo getLayoutInfo() const;

    // 建構傳給 crosvm 之磁碟掛載命令列參數
    std::vector<std::string> buildCrosvmDiskArgs() const;

private:
    std::string mBaseDir;
    StorageLayoutInfo mLayoutInfo;
};

} // namespace linux_bridge
} // namespace system
} // namespace android

#endif // LINUX_BRIDGE_STORAGE_MANAGER_H
```

### 2.3 宿主端 `StorageManager` C++ 實作細節 (`system/linux_bridge/src/storage_manager.cpp`)

```cpp
#include "storage_manager.h"
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <fcntl.h>
#include <unistd.h>
#include <iostream>
#include <fstream>

namespace android {
namespace system {
namespace linux_bridge {

StorageManager::StorageManager(std::string baseDir)
    : mBaseDir(std::move(baseDir)) {
    mLayoutInfo.baseRootfsPath = mBaseDir + "/base_rootfs.img";
    mLayoutInfo.customOverlayPath = mBaseDir + "/custom_overlay.img";
    mLayoutInfo.userHomePath = mBaseDir + "/user_home.img";
    mLayoutInfo.vmStateSnapshotPath = mBaseDir + "/vm_state.snapshot";
    mLayoutInfo.decryptedMapperPath = "/dev/mapper/user_home_decrypted";
    mLayoutInfo.isInitialized = false;
}

bool StorageManager::checkAvailableSpace(uint64_t requiredMb) const {
    struct statvfs stat;
    if (statvfs(mBaseDir.c_str(), &stat) != 0) {
        std::cerr << "[StorageManager] Failed to statvfs directory: " << mBaseDir << std::endl;
        return false;
    }
    uint64_t freeBytes = static_cast<uint64_t>(stat.f_bavail) * stat.f_frsize;
    uint64_t freeMb = freeBytes / (1024 * 1024);
    return freeMb >= requiredMb;
}

bool StorageManager::createSparseImage(const std::string& path, uint64_t sizeMb) {
    int fd = open(path.c_str(), O_RDWR | O_CREAT, 0600);
    if (fd < 0) {
        std::cerr << "[StorageManager] Failed to open/create file: " << path << std::endl;
        return false;
    }
    off_t targetSize = static_cast<off_t>(sizeMb) * 1024 * 1024;
    if (ftruncate(fd, targetSize) != 0) {
        std::cerr << "[StorageManager] Failed to ftruncate image: " << path << std::endl;
        close(fd);
        return false;
    }
    close(fd);
    return true;
}

bool StorageManager::initializeLayout() {
    mkdir(mBaseDir.c_str(), 0755);

    // 1. 建立 base_rootfs.img
    if (access(mLayoutInfo.baseRootfsPath.c_str(), F_OK) != 0) {
        if (!createSparseImage(mLayoutInfo.baseRootfsPath, BASE_ROOTFS_SIZE_MB)) return false;
    }

    // 2. 建立 custom_overlay.img
    if (access(mLayoutInfo.customOverlayPath.c_str(), F_OK) != 0) {
        if (!createSparseImage(mLayoutInfo.customOverlayPath, CUSTOM_OVERLAY_SIZE_MB)) return false;
    }

    // 3. 建立 user_home.img
    if (access(mLayoutInfo.userHomePath.c_str(), F_OK) != 0) {
        if (!createSparseImage(mLayoutInfo.userHomePath, USER_HOME_SIZE_MB)) return false;
    }

    // 4. 建立 vm_state.snapshot 佔位符
    if (access(mLayoutInfo.vmStateSnapshotPath.c_str(), F_OK) != 0) {
        createSparseImage(mLayoutInfo.vmStateSnapshotPath, 16); // 16MB 預設檔頭
    }

    mLayoutInfo.isInitialized = true;
    return true;
}

std::vector<std::string> StorageManager::buildCrosvmDiskArgs() const {
    std::vector<std::string> args;
    // Layer 1: base_rootfs.img (Read-only root disk)
    args.push_back("--disk");
    args.push_back(mLayoutInfo.baseRootfsPath + ",ro");

    // Layer 2: custom_overlay.img (Read-write overlay disk)
    args.push_back("--disk");
    args.push_back(mLayoutInfo.customOverlayPath + ",rw");

    // Layer 3: decrypted mapper for user_home.img
    args.push_back("--rwdisk");
    args.push_back(mLayoutInfo.decryptedMapperPath);

    return args;
}

} // namespace linux_bridge
} // namespace system
} // namespace android
```

---

## 3. Feature F-R2-003：LUKS2 CE 儲存加密 (LUKS2 CE Storage Encryption)

### 3.1 加密機制與 Key Lifecycle (Android CE Key Binding)

在 Non-Protected 模式下，為了防止實體抽取快閃記憶體或裝置遺失導致隱私外洩，`user_home.img` 必須強制採用 **LUKS2 (`aes-xts-plain64`, 512-bit key)** 進行磁碟加密。

金鑰生命週期與 Android 憑證加密 (CE) 流程高度整合：
1. **解鎖觸發 (`onUserUnlocking()`)**：
   - 使用者輸入 PIN/圖形/密碼，Android 解開 CE 快取。
   - `LinuxManagerService` 經由 Keystore2 / Keymaster 介面衍生出一組標籤為 `"aosp.linux.ce.user_home.luks2_master_key"` 的 512-bit 金鑰。
2. **容器開鎖 (`cryptsetup open`)**：
   - 宿主機 `linux_bridge` 呼叫 `LuksCrypto::openLuks2Container()`。
   - `libcryptsetup` 將 `/data/misc/linux/user_home.img` 解密並映射為 `/dev/mapper/user_home_decrypted`。
3. **crosvm 區塊裝置掛載**：
   - `/dev/mapper/user_home_decrypted` 作為第三塊區塊裝置 (`/dev/vdc`) 傳遞給 crosvm Guest，並掛載至 `/home/user`。
4. **鎖屏/登出擦除 (`onUserLoggingOut()` / Screen Lock)**：
   - 卸載 Guest `/home/user`。
   - 呼叫 `LuksCrypto::closeLuks2Container("user_home_decrypted")` 關閉 `dm-crypt` 裝置。
   - 使用 `memset_s` / `explicit_bzero` 擦除宿主機記憶體中的金鑰位元，防止 RAM 遭 Cold Boot 攻擊。

### 3.2 `LuksCrypto` C++ 介面設計 (`system/linux_bridge/src/luks_crypto.h`)

```cpp
/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * LuksCrypto: LUKS2 Container Encryption Management via libcryptsetup & dm-crypt
 */

#ifndef LINUX_BRIDGE_LUKS_CRYPTO_H
#define LINUX_BRIDGE_LUKS_CRYPTO_H

#include <string>
#include <vector>
#include <cstdint>

namespace android {
namespace system {
namespace linux_bridge {

class LuksCrypto {
public:
    static constexpr const char* DEFAULT_CIPHER = "aes";
    static constexpr const char* DEFAULT_CIPHER_MODE = "xts-plain64";
    static constexpr int DEFAULT_KEY_SIZE_BYTES = 64; // 512 bits (256-bit AES key * 2 for XTS)

    // 格式化指定映像檔為 LUKS2 容器 (使用 Android CE Key)
    static bool formatLuks2Container(
        const std::string& imagePath,
        const std::vector<uint8_t>& ceKey
    );

    // 解密並建立 dm-crypt 設備映射 (/dev/mapper/<mapperName>)
    static bool openLuks2Container(
        const std::string& imagePath,
        const std::string& mapperName,
        const std::vector<uint8_t>& ceKey
    );

    // 關閉 dm-crypt 映射裝置
    static bool closeLuks2Container(const std::string& mapperName);

    // 檢查指定 dm-crypt 映射是否存在且開啟
    static bool isContainerOpen(const std::string& mapperName);

    // 金鑰記憶體即時擦除 (Memory Wiping)
    static bool wipeKeyMemory(std::vector<uint8_t>& key);

    // 使用 HKDF-SHA256 衍生 LUKS2 專用子金鑰
    static std::vector<uint8_t> deriveCeSubKey(
        const std::vector<uint8_t>& masterCeKey,
        const std::string& infoTag = "aosp.linux.ce.user_home.luks2_master_key"
    );
};

} // namespace linux_bridge
} // namespace system
} // namespace android

#endif // LINUX_BRIDGE_LUKS_CRYPTO_H
```

### 3.3 `LuksCrypto` C++ 實作細節 (`system/linux_bridge/src/luks_crypto.cpp`)

```cpp
#include "luks_crypto.h"
#include <libcryptsetup.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <iostream>
#include <cstring>
#include <sys/stat.h>

namespace android {
namespace system {
namespace linux_bridge {

bool LuksCrypto::wipeKeyMemory(std::vector<uint8_t>& key) {
    if (!key.empty()) {
        OPENSSL_cleanse(key.data(), key.size());
        key.clear();
    }
    return true;
}

std::vector<uint8_t> LuksCrypto::deriveCeSubKey(
    const std::vector<uint8_t>& masterCeKey,
    const std::string& infoTag
) {
    std::vector<uint8_t> derivedKey(DEFAULT_KEY_SIZE_BYTES);
    EVP_PKEY_CTX *pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_HKDF, nullptr);
    if (!pctx) return {};

    if (EVP_PKEY_derive_init(pctx) <= 0 ||
        EVP_PKEY_CTX_set_hkdf_md(pctx, EVP_sha256()) <= 0 ||
        EVP_PKEY_CTX_set1_hkdf_key(pctx, masterCeKey.data(), masterCeKey.size()) <= 0 ||
        EVP_PKEY_CTX_add1_hkdf_info(pctx, infoTag.data(), infoTag.size()) <= 0) {
        EVP_PKEY_CTX_free(pctx);
        return {};
    }

    size_t outlen = derivedKey.size();
    if (EVP_PKEY_derive(pctx, derivedKey.data(), &outlen) <= 0) {
        EVP_PKEY_CTX_free(pctx);
        return {};
    }

    EVP_PKEY_CTX_free(pctx);
    return derivedKey;
}

bool LuksCrypto::formatLuks2Container(
    const std::string& imagePath,
    const std::vector<uint8_t>& ceKey
) {
    struct crypt_device *cd = nullptr;
    int r = crypt_init(&cd, imagePath.c_str());
    if (r < 0) {
        std::cerr << "[LuksCrypto] crypt_init failed for: " << imagePath << std::endl;
        return false;
    }

    struct crypt_params_luks2 params = {};
    params.dataType = CRYPT_LUKS2;

    r = crypt_format(cd, CRYPT_LUKS2, DEFAULT_CIPHER, DEFAULT_CIPHER_MODE,
                     nullptr, nullptr, DEFAULT_KEY_SIZE_BYTES, &params);
    if (r < 0) {
        std::cerr << "[LuksCrypto] crypt_format failed with code: " << r << std::endl;
        crypt_free(cd);
        return false;
    }

    r = crypt_keyslot_add_by_volume_key(cd, CRYPT_ANY_SLOT, nullptr, 0,
                                         reinterpret_cast<const char*>(ceKey.data()), ceKey.size());
    crypt_free(cd);
    return r >= 0;
}

bool LuksCrypto::openLuks2Container(
    const std::string& imagePath,
    const std::string& mapperName,
    const std::vector<uint8_t>& ceKey
) {
    struct crypt_device *cd = nullptr;
    int r = crypt_init(&cd, imagePath.c_str());
    if (r < 0) return false;

    r = crypt_load(cd, CRYPT_LUKS2, nullptr);
    if (r < 0) {
        std::cerr << "[LuksCrypto] crypt_load failed for " << imagePath << std::endl;
        crypt_free(cd);
        return false;
    }

    r = crypt_activate_by_passphrase(cd, mapperName.c_str(), CRYPT_ANY_SLOT,
                                      reinterpret_cast<const char*>(ceKey.data()), ceKey.size(), 0);
    crypt_free(cd);
    return r >= 0;
}

bool LuksCrypto::closeLuks2Container(const std::string& mapperName) {
    struct crypt_device *cd = nullptr;
    int r = crypt_init_by_name(&cd, mapperName.c_str());
    if (r < 0) return false;

    r = crypt_deactivate(cd, mapperName.c_str());
    crypt_free(cd);
    return r >= 0;
}

bool LuksCrypto::isContainerOpen(const std::string& mapperName) {
    std::string path = "/dev/mapper/" + mapperName;
    struct stat st;
    return (stat(path.c_str(), &st) == 0);
}

} // namespace linux_bridge
} // namespace system
} // namespace android
```

---

## 4. `Android.bp` 建置檔整合規劃 (`system/linux_bridge/Android.bp`)

新增 `src/storage_manager.cpp` 與 `src/luks_crypto.cpp` 至建置來源，並引入 `libcryptsetup` 與 `libcrypto` 共享函式庫依賴：

```blueprint
// Android.bp for system/linux_bridge native daemon

cc_binary {
    name: "linux_bridge",
    srcs: [
        "main.cpp",
        "socket_server.cpp",
        "vsock_framing.cpp",
        "vsock_server.cpp",
        "hmac_auth.cpp",
        "src/storage_manager.cpp",
        "src/luks_crypto.cpp",
    ],
    shared_libs: [
        "liblog",
        "libutils",
        "libcutils",
        "libbinder",
        "libcrypto",
        "libcryptsetup",
    ],
    cflags: [
        "-Wall",
        "-Werror",
        "-Wextra",
        "-std=c++20",
    ],
    init_rc: ["linux_bridge.rc"],
}
```

---

## 5. E2E 測試與邊界情境驗證矩陣 (Verification & Test Alignment)

本分析成果完全對齊 `TEST_INFRA.md` 針對 F-R2-002 與 F-R2-003 所規劃之 Tier 1 與 Tier 2 測試用例：

| 測試編號 | 測試名稱 | 涵蓋功能 | 驗證方法與斷言 |
|---|---|---|---|
| `T1-31` | MountReadOnlyBaseRootfs | F-R2-002 | 驗證 `base_rootfs.img` 掛載於 `/` 且選項為 `ro`。 |
| `T1-32` | MountOverlayfsWritableLayer | F-R2-002 | 驗證 OverlayFS 覆蓋 `/etc`, `/var`, `/usr` 且可寫。 |
| `T1-33` | MountLuksDecryptedUserHome | F-R2-002 | 驗證 `/dev/mapper/user_home_decrypted` 掛載至 `/home/user`。 |
| `T1-34` | VmStateSnapshotCreation | F-R2-002 | 驗證 `/data/misc/linux/vm_state.snapshot` 快照檔存在。 |
| `T1-35` | OverlayfsDiffPersistence | F-R2-002 | 驗證重開機後 OverlayFS 異動層資料完整保留。 |
| `T1-36` | DeriveKeyFromCeKeymaster | F-R3-003 | 驗證透過 Keystore2 產生 256/512-bit CE 加密金鑰。 |
| `T1-37` | CryptsetupOpenOnUserUnlock | F-R3-003 | 驗證解鎖時以 CE 金鑰開啟 LUKS2 映射裝置。 |
| `T1-38` | MountDecryptedMapperToUserHome | F-R3-003 | 驗證 `/dev/mapper/user_home_decrypted` 成功掛載。 |
| `T1-39` | UnmountAndCloseOnUserLock | F-R3-003 | 驗證鎖屏時自動關閉映射並呼叫 `wipeKeyMemory`。 |
| `T1-40` | Aes256XtsCipherVerification | F-R3-003 | 驗證 LUKS2 檔頭採用 `aes-xts-plain64` 加密。 |
| `T2-31` | PreventWriteBaseRootfs | F-R2-002 (T2) | 嘗試寫入 `base_rootfs.img` 回傳 `EROFS` 唯讀錯誤。 |
| `T2-32` | StorageFullHandling | F-R2-002 (T2) | 透過 `statvfs` 預先攔截 `ENOSPC` 空間爆滿。 |
| `T2-36` | FailDecryptionWrongKey | F-R3-003 (T2) | 傳入錯誤 CE key 開啟 LUKS 容器應被拒絕 (`r < 0`)。 |
| `T2-37` | KeyWipeOnLockScreen | F-R3-003 (T2) | 驗證鎖屏時 RAM 中的金鑰位元被 `OPENSSL_cleanse` 清除。 |
| `T2-38` | CorruptedLuksHeaderRecovery | F-R3-003 (T2) | 檔頭損毀時回傳錯誤並提示可重新 `formatLuks2Container`。 |

---

## 6. 結論與實作建議 (Conclusions & Recommendations)

1. **模組解耦與封裝**：建議 Worker 將 `StorageManager` 與 `LuksCrypto` 獨立放置於 `system/linux_bridge/src/` 中，保持主行程與 socket 邏輯乾淨。
2. **記憶體安全與抹除**：金鑰處理必須全程使用 `OPENSSL_cleanse()` 或 `memset_s()`，避免 C++ `std::string` 或 `std::vector` 在記憶體中殘留金鑰副本。
3. **區塊裝置映射順序**：`crosvm` 啟動前必須確認 `/dev/mapper/user_home_decrypted` 已完成建立與 EXT4 格式檢查，避免 crosvm 因區塊裝置不存在而啟動失敗。
