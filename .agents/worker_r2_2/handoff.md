# Handoff Report — Guest Portal Real Event Consumption (worker_r2_2)

## 1. Observation (觀察事實)

在 `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/portal.rs` 進行修改前與修改後的程式碼比對：

### 原始問題 (Hardcoded Mock Responses)
在重構前，`dispatch_portal_request` 中存在硬編碼假資料 (lines 44–62)：
```rust
"camera.request" | "camera.status" => {
    PortalResponse::ok(req.id, serde_json::json!({
        "status": "available",
        "device": "/dev/video0"
    }))
}
"audio.request" | "audio.status" => {
    PortalResponse::ok(req.id, serde_json::json!({
        "status": "available",
        "backend": "pipewire"
    }))
}
"location.get" | "location.request" => {
    PortalResponse::ok(req.id, serde_json::json!({
        "latitude": 0.0,
        "longitude": 0.0,
        "accuracy": "mock"
    }))
}
```
此實作完全忽略 Host (`LinuxPortalService.java`) 透過 VSOCK Port 5000 推送的動態事件，無論系統狀態為何皆固定回傳硬編碼 `(0.0, 0.0)`、`"mock"` 與 static `"available"`。

### 重構後實作 (Dynamic PortalState & Dual-Mode Event Ingestion)
在 `/Users/iml1s/Documents/mine/aosp-linux/guest/bridge-agent/src/portal.rs` 中實作：
1. **靜態與線程安全狀態容器 (`PortalState`)**:
   - `LocationEvent`：包含 `latitude`, `longitude`, `accuracy`, `timestamp`（使用 Serde 欄位別名相容 `Latitude`, `Longitude`, `Accuracy`）。
   - `CameraFrameEvent`：包含 `device`, `width`, `height`, `fps`, `status`, `timestamp`（相容 `Device`, `Width`, `Height`, `Fps`, `Status`）。
   - `AudioPcmEvent`：包含 `backend`, `sample_rate`, `channels`, `status`, `timestamp`（相容 `Backend`, `SampleRate`, `Channels`, `Status`）。
   - `HostPortalEvent`：Serde tagged enum (rename `"location"`, `"camera"`, `"audio"`)。
   - `PortalState` 與 `GLOBAL_PORTAL_STATE` (`Arc<RwLock<PortalState>>` + `OnceLock`)。

2. **雙模式事件解析與動態更新 (`handle_portal_session`)**:
   - 解析 Tagged Host Event (`HostPortalEvent`) 或 Untagged/Legacy Event（如 Host `LinuxPortalService.java` 的 `{"Latitude": 25.03, "Longitude": 121.56, "Accuracy": 5.0}`），若符合 Event 格式則更新 `PortalState` 並繼續讀取下一列。
   - 若為 `PortalRequest` JSON 指令，呼叫 `dispatch_portal_request` 並將 JSON `PortalResponse` 寫回串流。

3. **完全移除硬編碼 Mock 回應 (`dispatch_portal_request`)**:
   - 查詢 `PortalState`：
     - 若 `last_location` 為 `Some(loc)`，回傳真實動態座標 JSON；若為 `None`，回傳 `PortalResponse::err(req.id, "Location unavailable: No Host location update received")`。
     - 若 `last_camera` 為 `Some(cam)`，回傳真實相機狀態 JSON；若為 `None`，回傳 `PortalResponse::err(req.id, "Camera unavailable: No active Host camera stream")`。
     - 若 `last_audio` 為 `Some(aud)`，回傳真實音訊狀態 JSON；若為 `None`，回傳 `PortalResponse::err(req.id, "Audio unavailable: No active Host audio stream")`。

---

## 2. Logic Chain (推理邏輯鏈)

1. **事件驅動狀態設計**:
   Host 系統 (Android `LinuxPortalService`) 會透過 VSOCK Port 5000 主動推送硬體 Portal 事件（如地理位置更新、相機狀態更新、錄音狀態更新）。Guest Agent 必須維持全域線程安全狀態 (`Arc<RwLock<PortalState>>`) 記錄最新接收到的硬體狀態。
2. **雙模式輸入解包 (Dual-Mode Ingestion)**:
   - 輸入串流包含兩類資料：(1) Host 推送的事件 notification (單向)；(2) Guest 端發出的 Portal RPC 請求 (雙向)。
   - 在 `handle_portal_session` 中，優先比對 `HostPortalEvent` (Serde tagged enum) 與相容欄位名稱 (`Latitude`, `Longitude`, `Accuracy` 等)，成功解析即更新 `PortalState` 且不產生回傳應答。
   - 若非事件格式，則轉由 `serde_json::from_str::<PortalRequest>` 解析並執行 RPC dispatch 產生 `PortalResponse`。
3. **消除 Mock 資料與 Fail-Closed 機制**:
   - 徹底刪除所有硬編碼 `(0.0, 0.0)`、`"mock"` 與 `"available"`。
   - 當 `PortalState` 尚未收到 Host 事件時，嚴格回傳 `success: false` 的 `PortalResponse::err`，防止未初始化的模擬資料污染系統。

---

## 3. Caveats (注意事項與未涵蓋範圍)

1. **音訊 PCM 二進位數據**:
   - 本次修改聚焦於控制面 metadata 與狀態更新 (`AudioPcmEvent`)。對於未來高頻音訊 PCM 二進位串流，可沿用此 `PortalState` metadata 作為控制面，另接 PipeWire / ALSA 虛擬音訊裝置。
2. **單元測試併發隔離**:
   - 由於 Rust 單元測試預設多線程平行執行，全域 `GLOBAL_PORTAL_STATE` 可能在平行測試中互相干擾。本實作額外提供 `dispatch_portal_request_with_state` 函式，使單元測試能針對獨立 `PortalState` 實例做 100% 確定性驗證。

---

## 4. Conclusion (結論)

已完成 `guest/bridge-agent/src/portal.rs` 的完整重構：
- 實現了線程安全的 `PortalState` 容器 (`Arc<RwLock<PortalState>>`)。
- 實現了 `handle_portal_session` 的雙模式事件解析與狀態更新（相容 Serde tagged enum 及欄位別名 `Latitude`, `Longitude`, `Accuracy`, `device`, `status`）。
- 徹底清除所有硬編碼 `(0.0, 0.0)`、`"mock"` 及靜態 `"available"` 假資料。
- 未初始化時回傳標準 `PortalResponse::err` 錯誤，有事件時回傳動態 JSON 資料。
- 更新並補充了單元測試，全部 33 個 Rust 測試（包含 9 個 Portal 模組測試與 empirical 測試）全數通過。

---

## 5. Verification Method (驗證方法)

1. **執行測試指令**:
   ```bash
   export PATH="$HOME/.cargo/bin:$PATH"
   cargo test --manifest-path guest/bridge-agent/Cargo.toml
   ```
2. **測試結果摘要**:
   ```text
   running 33 tests
   test auth::tests::test_hmac_sha256_computation ... ok
   test auth::tests::test_parse_secret_from_cmdline ... ok
   test auth::tests::test_perform_handshake_failure ... ok
   test auth::tests::test_perform_handshake_success ... ok
   test auth::tests::test_perform_handshake_timeout ... ok
   test auth::tests::test_verify_token_all_zero_rejected ... ok
   test auth::tests::test_verify_token_empty_rejected ... ok
   test auth::tests::test_verify_token_mismatch_rejected ... ok
   test auth::tests::test_verify_token_valid ... ok
   test empirical_tests::empirical_tests::test_auth_comprehensive_empirical ... ok
   test empirical_tests::empirical_tests::test_fd_leak_stress ... ok
   test empirical_tests::empirical_tests::test_portal_payload_overflow_rejection ... ok
   test empirical_tests::empirical_tests::test_pty_disconnect_no_sigabrt_stress ... ok
   test empirical_tests::empirical_tests::test_pty_heavy_concurrent_load_stress ... ok
   test empirical_tests::empirical_tests::test_pty_payload_overflow_rejection ... ok
   test empirical_tests::empirical_tests::test_silent_socket_handshake_timeout_empirical ... ok
   test empirical_tests::empirical_tests::test_wayland_full_duplex_no_mutex_deadlock_stress ... ok
   test portal::tests::test_dispatch_audio_status_dynamic ... ok
   test portal::tests::test_dispatch_camera_status_dynamic ... ok
   test portal::tests::test_dispatch_file_write_and_read ... ok
   test portal::tests::test_dispatch_location_get_dynamic ... ok
   test portal::tests::test_handle_portal_session_payload_size_limit ... ok
   test portal::tests::test_handle_portal_session_tagged_camera_event ... ok
   test portal::tests::test_handle_portal_session_untagged_location_event ... ok
   test portal::tests::test_uninitialized_portal_state_returns_error ... ok
   test pty::tests::test_pty_header_encode_parse ... ok
   test pty::tests::test_pty_master_open_and_slave_name ... ok
   test pty::tests::test_pty_payload_len_limit ... ok
   test pty::tests::test_pty_resize ... ok
   test vsock::tests::test_vsock_listener_bind_free_port ... ok
   test wayland::tests::test_get_wayland_socket_path_default ... ok
   test wayland::tests::test_proxy_bi_directional ... ok
   test wayland::tests::test_proxy_split_unix_stream_full_duplex ... ok

   test result: ok. 33 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 15.85s
   ```
3. **無效化條件 (Invalidation Conditions)**:
   - 若 `portal.rs` 中重新出現任何 `(0.0, 0.0)` 或 `"mock"` 硬編碼字串。
   - 若 `handle_portal_session` 無法更新 `PortalState` 或無法解析 Host 推送事件。
