# AOSP Dual-OS 完整技術規劃文件索引

> 研究日期：2026-08-06  
> AOSP branch：refs/heads/main  
> 文件版本：1.0

---

## 文件結構

| 章節 | 檔案 | 內容 |
|------|------|------|
| 第 1 章 | `01-executive-summary.md` | 執行摘要、核心命題、已驗證 AOSP 能力 |
| 第 2 章 | `02-architecture-decisions.md` | 最終架構決策、ADR 一覽表、設計原則 |
| 第 3 章 | `03-aosp-capability-evidence.md` | 現有 AOSP 能力實證（第一手原始碼）|
| 第 4 章 | `04-rejected-approaches.md` | 不可行或不建議方案完整分析 |
| 第 5 章 | `05-system-architecture.md` | 完整架構圖、序列圖、Trust Boundary |
| 第 6-7 章 | `06-07-trust-boundary-source-map.md` | Trust Boundary 詳細規範、AOSP 修改地圖 |
| 第 8 章 | `08-framework-api.md` | Framework API 設計（AIDL、狀態機、權限）|
| 第 9-10 章 | `09-10-guest-design-lifecycle.md` | Linux Guest 設計、VM Lifecycle、SLO |
| 第 11-12 章 | `11-12-bridge-terminal.md` | Bridge 協議、原生 Terminal 完整設計 |
| 第 13-16 章 | `13-16-gui-launcher-files-network.md` | GUI/WindowManager、Launcher、檔案、網路 |
| 第 17-20 章 | `17-20-audio-input-portal-power.md` | 音訊、輸入、Hardware Portal、電源效能 |
| 第 21-22 章 | `21-22-security-ota.md` | 安全模型、SELinux、OTA、Rollback |
| 第 23-28 章 | `23-28-multiuser-build-test-cicd-observability-ux.md` | 多使用者、建置、測試、CI/CD、UX |
| 第 29 章 | `29-roadmap.md` | 完整 Roadmap（Phase 0-11）|
| 第 30 章 | `30-team-structure.md` | 團隊拆分、依賴關係、人力估算 |
| 第 31 章 | `31-risk-register.md` | P0/P1/P2/P3 風險登錄表 |
| 第 32-35 章 | `32-35-acceptance-adr-todo-references.md` | 驗收矩陣、ADR、待驗證事項、來源引用 |

---

## 關鍵結論速查

### 架構決策
**選用**：AVF + crosvm + KVM + non-protected VM + Debian 12 ARM64 + authenticated vsock bridge + Android 原生 Terminal UI

**放棄**：WebView/ttyd、IP-based auth、synthetic APK、VNC-only GUI、permissive SELinux、chroot/PRoot/Termux

### AOSP 現狀（2026-08-06 驗證）
- TerminalView.kt = **WebView subclass**（非原生，需替換）
- gRPC over vsock = **不支援**（b/372666638，需自製 framing）
- virtio-gpu = **EXPERIMENTAL**（testing flag，非正式）
- VirtualizationService AIDL = **EXISTING（完整）**
- crosvm binary = `/apex/com.android.virt/bin/crosvm`（**EXISTING**）

### 1.0 工程規模（粗估）
- 工程師：12-26 人
- 時程：18-24 個月
- 所有估算均為假設值，實際視資源調整

---

## 快速連結：各團隊關注章節

| 團隊 | 主要章節 |
|------|---------|
| AOSP Framework | 7, 8 |
| Virtualization | 3, 9, 10 |
| Linux Guest | 9, 22 |
| Terminal | 12 |
| Graphics/Wayland | 13 |
| Android UX | 14, 28 |
| Bridge/Portal | 11, 15, 19 |
| Security | 6, 21 |
| Build/Release | 24, 26 |
| QA | 25, 32 |
| All | 29, 30, 31 |
