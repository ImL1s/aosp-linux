# 第三十章：團隊拆分與依賴關係

## 30.1 工程團隊劃分

### Team 1：AOSP Framework Team
**職責**：
- `LinuxManagerService`、`LinuxBridgeService`、`LinuxPortalService`
- Framework AIDL 設計與實作
- `SystemServer` 整合
- AppOps、權限模型
- `dumpsys` / `cmd linux` debug 工具

**必要技能**：Java/Kotlin、Android Framework、Binder/AIDL

**關鍵依賴**：Bridge Team 提供 RPC 介面規格；Security Team 提供 SELinux domain 規劃

---

### Team 2：Virtualization Team
**職責**：
- AVF / crosvm 整合與設定
- VM lifecycle 管理
- virtmgr 修改（若必要）
- VM 資源限制（cgroup, balloon）
- Suspend/Resume 實作
- Guest image 載入與 AVB 驗證

**必要技能**：Rust、Android 虛擬化架構、KVM、crosvm

**關鍵依賴**：Guest Team 提供 kernel 設定；Security Team 提供 crosvm seccomp

---

### Team 3：Linux Guest Team
**職責**：
- Debian 12 ARM64 rootfs 建立
- Guest kernel 設定（virtio drivers）
- systemd 設定
- Guest image builder pipeline
- Guest A/B update 機制
- LUKS2 加密設定
- APT repo 管理

**必要技能**：Linux 系統管理、Debian packaging、kernel 設定、Rust（agents）

---

### Team 4：Terminal Team
**職責**：
- `LinuxTerminal` App（原生 Surface Canvas 渲染）
- libvterm JNI 整合
- `TerminalInputConnection`（CJK IME）
- 三種觸控模式狀態機
- vsock PTY client
- Session 持久化
- 安全輸入 mode

**必要技能**：Android UI、Kotlin/Java、JNI/C、Unicode/IME、Android Input

---

### Team 5：Graphics / Wayland Team
**職責**：
- Guest wayland-agent（Sommelier-like）
- `LinuxWindowBridgeService`
- `LinuxAppProxyActivity`
- virtio-gpu virglrenderer/gfxstream
- dma-buf buffer sharing
- per-window SurfaceControl 映射
- Recents 整合
- Desktop Mode

**必要技能**：Wayland 協議、virtio-gpu、Android SurfaceFlinger/WindowManager、OpenGL/Vulkan

---

### Team 6：Android UX Team
**職責**：
- `LinuxSettings` App
- Launcher3 Linux App 整合（`LinuxAppRegistryService`）
- First-run setup flow
- 通知設計
- Settings UI
- Linux Files App（DocumentsProvider UI）

**必要技能**：Android UI/UX、Kotlin、Material Design

---

### Team 7：Bridge / Portal Team
**職責**：
- `linux_bridge` C++ daemon
- Bridge RPC protocol 設計與實作
- HMAC-SHA256 auth
- `xdg-portal-agent`（Guest）
- XDG Portal → Android Portal 映射
- virtio-snd AudioFocus
- virtiofs 設定

**必要技能**：C++/Rust、Linux vsock、XDG Desktop Portal、Android Audio/Camera

---

### Team 8：Security Team
**職責**：
- SELinux 所有新增 domain 設計
- NEVERALLOW 規則
- crosvm seccomp 審計
- HMAC/HKDF 實作審核
- Threat model 審查
- 滲透測試協調
- Fuzzing 整合
- CVE 追蹤

**必要技能**：Android 安全、SELinux、密碼學、漏洞分析

---

### Team 9：Kernel / Device Team
**職責**：
- ARM64 reference device bring-up
- Guest kernel 維護（virtio patches, KVM patches）
- Host kernel 修改（若必要）
- Cuttlefish 支援
- 硬體測試（各裝置型號）

**必要技能**：Linux kernel、ARM64、裝置驅動、Cuttlefish

---

### Team 10：Build / Release Team
**職責**：
- Soong Android.bp 整合
- Guest image builder pipeline（CI）
- AVB 簽章流程
- SBOM 產生
- Release engineering（canary, beta, stable）
- Reproducible build 驗證

**必要技能**：Android build system、Docker、CI/CD、Python

---

### Team 11：QA / Compatibility Team
**職責**：
- CTS/VTS 測試執行與分析
- 自動化 E2E 測試（Cuttlefish + 真機）
- Terminal 功能測試矩陣
- 效能測試（SLO 驗證）
- 多使用者測試
- OTA 測試
- 相容性測試（不同 ARM64 裝置）

**必要技能**：Android testing、Espresso/UIAutomator、Python 自動化

---

## 30.2 團隊依賴關係

```
Phase 1（Cuttlefish Bring-up）：
  Virtualization Team + Guest Team + Kernel Team
  → 並行：Guest image builder

Phase 2（Framework API）：
  Framework Team（主）
  → Bridge Team 提供 RPC spec
  → Security Team 提供 domain 規劃

Phase 3（Terminal）：
  Terminal Team（主）+ Bridge Team
  → 依賴：Phase 1（VM 可啟動）+ Phase 2（LinuxManager）

Phase 4-5（File + Network）：
  Bridge/Portal Team + UX Team
  → 並行：不阻塞 Terminal

Phase 6（GUI）：
  Graphics/Wayland Team（主）
  → 依賴：Phase 1（GPU 可用）+ Phase 2

Phase 7（Audio + Portal）：
  Bridge/Portal Team
  → 依賴：Phase 6（Wayland 可用）

Phase 8（Security）：
  Security Team（主）+ 所有 Team 配合
  → 不可並行：需要功能完成後才能全面 hardening

Phase 9（OTA + Multi-user）：
  Guest Team + Framework Team + Virtualization Team
  → 並行可開始 Phase 10 部分工作

Phase 10（CTS/VTS）：
  QA Team（主）
  → 依賴：Phase 8（security hardening）完成

Phase 11（Beta → 1.0）：
  所有 Team + Release Team
```

## 30.3 人力估算（粗略）

| 團隊 | 人數 | 關鍵技能 |
|------|------|---------|
| AOSP Framework | 2-3 | Android Framework 資深 |
| Virtualization | 2 | crosvm/KVM 專家 |
| Linux Guest | 2 | Linux 系統 |
| Terminal | 2 | Android UI + VT 終端 |
| Graphics/Wayland | 2-3 | GPU 虛擬化 |
| Android UX | 1-2 | Android 設計 |
| Bridge/Portal | 2 | C++/Rust + 協議 |
| Security | 2 | Android 安全 |
| Kernel/Device | 2 | 嵌入式/核心 |
| Build/Release | 1-2 | CI/CD |
| QA | 2-3 | 測試自動化 |
| **合計** | **22-26** | |

最小可行團隊（以關鍵路徑為主）：**12-15 人**，但會延長時程。

所有人力數字為粗略估算，假設全職工程師，實際視項目範圍與資源調整。
