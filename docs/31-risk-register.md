# 第三十一章：P0/P1/P2/P3 風險登錄表

## P0 風險（阻擋性，必須在 Phase 0-1 解決）

| # | 風險 | Severity | Likelihood | 影響 | 偵測方式 | 緩解 | Fallback | Owner |
|---|------|---------|-----------|------|---------|------|---------|-------|
| R-P0-1 | AVF non-protected VM 無法載入自訂 Debian rootfs | Critical | Low（AOSP 已有 InstallerService）| 整個架構不可行 | Phase 1 Cuttlefish 驗證 | 確認 VirtualMachineCustomImageConfig API | 改用 chroot（不接受）→ 重新評估架構 | Virtualization Team |
| R-P0-2 | ARM64 KVM 裝置不支援（無 `/dev/kvm`）| Critical | Medium（許多商業裝置未開放）| 無法使用 KVM 加速 | 確認目標裝置 KVM 支援 | 選擇支援 KVM 的參考裝置 | 阻擋整個專案 | Kernel Team |
| R-P0-3 | AF_VSOCK 在目標 Android 版本不可用 | Critical | Low（AOSP 已有 virtio-vsock）| Bridge 無法實作 | Phase 0 AOSP 原始碼確認 | 已確認 virtio-vsock EXISTING | 改用 TCP（安全降級）| Bridge Team |
| R-P0-4 | crosvm seccomp 過濾阻擋必要 syscall | High | Medium | VM 無法啟動 | Phase 1 測試 | 確認 crosvm.te 允許列表 | 最小化 syscall 需求 | Security Team |

---

## P1 風險（重大，必須在對應 Phase 解決）

| # | 風險 | Severity | Likelihood | 影響 | 偵測 | 緩解 | Fallback | Blocking Milestone |
|---|------|---------|-----------|------|------|------|---------|-------------------|
| R-P1-1 | virtio-gpu gfxstream/virglrenderer 在非 Cuttlefish 真機不穩定 | High | High（當前為 EXPERIMENTAL）| GUI 功能不可用 | Phase 6 真機測試 | 使用 virglrenderer（更成熟）| GUI 延後到 2.0，1.0 僅支援 Terminal | Phase 6 |
| R-P1-2 | per-window Wayland→Android Task 映射延遲或不穩定 | High | Medium | Linux GUI App 體驗差 | Phase 6 整合測試 | 逐步測試各種 Linux App | 退回全螢幕 Surface 作為 fallback | Phase 6 |
| R-P1-3 | Android IME 在 Terminal 的 CJK 組字精確度 | High | Medium（WebView/ttyd 已有此問題）| 中文使用者體驗差 | Phase 4 CJK 測試矩陣 | 使用 BaseInputConnection 正確實作 | 僅支援實體鍵盤 CJK | Phase 4 |
| R-P1-4 | Guest VM suspend/resume 狀態不一致 | High | Medium | 隨機資料損壞 | Phase 9 Suspend/Resume 壓力測試 | 每次 suspend 前強制 sync | 禁止 suspend（僅 stop）| Phase 9 |
| R-P1-5 | LUKS2 CE key binding 在 Android 鎖屏後失敗 | High | Low（設計已考慮）| user home 無法解鎖 | Phase 8 鎖屏測試 | 完整 CE key lifecycle 測試 | fallback：無 LUKS（安全降級，不接受）| Phase 8 |
| R-P1-6 | crosvm 版本升級破壞現有 VM config | High | Medium | OTA 後 VM 無法啟動 | APEX 版本追蹤 | 固定 crosvm API 版本，測試相容性 | rollback crosvm APEX | Build/Release Team |
| R-P1-7 | Android CTS 因 LinuxManagerService 注冊失敗 | High | Low | CTS 阻擋 release | Phase 10 CTS 測試 | 不在 CTS test list 的服務名稱 | 從 SystemServer 移除（不接受）| QA Team |
| R-P1-8 | Guest Linux App 因 Wayland compositor crash 導致 Android Task 殭屍 | High | Medium | 使用者體驗嚴重中斷 | Phase 6 compositor crash 測試 | 偵測 compositor crash → 清理所有 Tasks | 使用者手動清理 | Graphics Team |

---

## P2 風險（中等，需在 1.0 前解決）

| # | 風險 | Severity | Likelihood | 緩解 | Blocking Milestone |
|---|------|---------|-----------|------|-------------------|
| R-P2-1 | 電池消耗高於預期（VM overhead）| Medium | High | 嚴格 suspend 政策；balloon 記憶體管理 | Phase 8 |
| R-P2-2 | Guest OTA 下載失敗後狀態損壞 | Medium | Medium | 原子性下載 + hash 驗證；保留舊 slot | Phase 9 |
| R-P2-3 | virtiofs 效能不足（大檔案 I/O）| Medium | Medium | 設定 virtiofs cache 模式；benchmark | Phase 5 |
| R-P2-4 | Linux App .desktop 惡意 Exec 注入 | Medium | Low | 嚴格 sanitizeExec() 函式；僅允許絕對路徑 | Phase 6 |
| R-P2-5 | USB passthrough 潛在逃逸風險 | Medium | Low | 預設完全禁用；如需啟用需嚴格評估 | Phase 8 |
| R-P2-6 | 多使用者情境下 CE key 衍生錯誤 | Medium | Low | 完整 per-user key 測試；測試 user switch | Phase 9 |
| R-P2-7 | Bridge disconnect 後 orphan PTY session | Medium | Medium | pty-agent heartbeat；session cleanup on disconnect | Phase 3 |
| R-P2-8 | ARM64 reference device vendor kernel 缺少 EROFS/LUKS2 | Medium | Medium | 確認並維護裝置 kernel 設定清單 | Phase 10 |
| R-P2-9 | VPN bypass：Linux DNS 不走 Host resolver | Medium | Low | 強制 Guest DNS 指向 Host gateway；測試 DNS leak | Phase 5 |
| R-P2-10 | snapshot 操作中 VM crash 導致 image 損壞 | Medium | Medium | snapshot 前 sync；使用 atomic copy；驗證 image integrity | Phase 9 |

---

## P3 風險（低，需在 post-1.0 評估）

| # | 風險 | 影響 | 備注 |
|---|------|------|------|
| R-P3-1 | AVF APEX 版本不穩定（upstream 重大改動）| Medium | 追蹤 AOSP AVF changelogs |
| R-P3-2 | Debian 12 EOL（2028 年）後的升級路徑 | Low | 規劃 Debian 13 升級計畫 |
| R-P3-3 | virtio-gpu Vulkan（Venus）成熟後的遷移 | Low | 等待 Venus 穩定後評估 |
| R-P3-4 | Android 新版本 WindowManager API 破壞性變更 | Low | 追蹤 Android 版本 compat |
| R-P3-5 | 外接螢幕 Desktop Mode 多螢幕複雜場景 | Low | post-1.0 feature |
| R-P3-6 | NFC/Bluetooth portal 安全評估 | Low | 1.0 完全禁用，post-1.0 評估 |
| R-P3-7 | Guest Linux App 無障礙（a11y）整合 | Low | post-1.0，需 AT-SPI bridge |
| R-P3-8 | foldable 裝置 dual-display Linux 場景 | Low | post-1.0 |
| R-P3-9 | 供應鏈攻擊（Debian 套件庫）| Medium | 使用 Debian 快照，APT GPG 驗證 |
| R-P3-10 | Terminal OSC 52 clipboard 安全性細節 | Low | 確認 rate limit + user consent |
