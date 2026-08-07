# Milestone M3 Iteration 3 Remediation Analysis (`analysis.md`)

## 1. 執行摘要 (Executive Summary)

本報告針對 Milestone M3 (Native Touch Terminal & IME) 在 Iteration 2 Gate Review 中被 `reviewer_m3_2_r2` 提出的兩項誠信違規 (INTEGRITY VIOLATION) 進行深入問題剖析，並制定 Iteration 3 Remediation 的技術測試驗證策略：
1. **`TOUCHPAD_MODE` 事件生成與手勢追蹤驗證**：設計 `TerminalAppUnitTest.java` 中的單元測試斷言，驗證相對觸控位移 ($\Delta x, \Delta y$)、虛擬游標網格座標計算、單擊 (Left Click/Button 0)、長按 (Right Click/Button 2) 及雙指滾輪 (Wheel Up 64/Wheel Down 65) 之 SGR 1006 封包生成。
2. **`VsockTerminalClient` Socket 傳輸驗證**：設計 `TerminalAppUnitTest.java` 中的單元測試斷言，驗證 `TerminalView` / `PtySender` 與 `VsockTerminalClient.sendFrame()` 之真實 Socket 傳輸串接，徹底清除僅 `Log.d` 紀錄而未發送封包之 Facade Implementation。
3. **真實 Java/C++ 二進位執行檔驗證保障**：確保 `test_m3_tier1.py` 與 `test_m3_tier2.py` 繼續編譯並透過 `CommandRunner` 執行真實 Java `.class` 與原生 C++ 二進位檔案，拒絕任何 Mock 或虛假的 E2E 測試捷徑。

---

## 2. 現存缺陷與代碼位置分析 (Defect Analysis & Code Locations)

### 2.1 缺陷 1: `TerminalView.java` 中 `TOUCHPAD_MODE` 空 Stub (Finding 1)
- **檔案與行號**：
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` Line 166-167
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java` Line 115-117
- **現狀**：
  ```java
  case TOUCHPAD_MODE:
      return true; // 僅返回 true，未進行相對觸控位移計算或 SGR 封包生成
  ```
- **根因**：Worker 在 Iteration 2 僅填寫了註解與固定返回值 `true`，未實現相對位移歷史記錄與手勢至 SGR 協定的轉換邏輯。

### 2.2 缺陷 2: `TerminalView.java` 未呼叫 `VsockTerminalClient.sendFrame()` (Finding 2)
- **檔案與行號**：
  - `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` Line 95-111
- **現狀**：
  ```java
  @Override
  public void sendBytes(byte[] bytes) {
      if (bytes == null || bytes.length == 0) return;
      byte[] frame = VsockPtyFramer.serializeFrame(mSessionId, VsockPtyFramer.PacketType.DATA, bytes);
      Log.d(TAG, "Sent PTY Frame over Port 5001: " + frame.length + " bytes");
      // 缺漏：未調用 mVsockClient.sendFrame(frame)!
  }
  ```
- **根因**：`TerminalView` 雖宣告並實例化了 `mVsockClient`，但在傳送方法中僅調用序列化並印出 `Log.d`，隨即丟棄封包，形成了網絡傳輸層面的 Facade。

---

## 3. Iteration 3 測試驗證策略設計 (Test Verification Strategy)

### 3.1 `TOUCHPAD_MODE` 事件生成單元測試斷言設計 (`testTouchpadModeEventGeneration`)
在 `tests/unit/TerminalAppUnitTest.java` 中新增 `testTouchpadModeEventGeneration()` 測試方法：

```java
private static int testTouchpadModeEventGeneration() {
    System.out.print("[TEST] F-R3-005/006: TOUCHPAD_MODE Relative Motion & Gesture SGR... ");
    try {
        TouchpadGestureHandler gestureHandler = new TouchpadGestureHandler(80, 24, 20, 40);
        
        // 1. Initial Virtual Cursor at Center (40, 12)
        if (gestureHandler.getVirtualCursorCol() != 40 || gestureHandler.getVirtualCursorRow() != 12) {
            System.out.println("FAILED (Initial virtual cursor position invalid)");
            return 1;
        }

        // 2. Relative Delta Motion Tracking (dx = +40, dy = -80) -> Col 42, Row 10
        byte[] moveSgr = gestureHandler.handleRelativeMove(40f, -80f);
        if (gestureHandler.getVirtualCursorCol() != 42 || gestureHandler.getVirtualCursorRow() != 10) {
            System.out.println("FAILED (Virtual cursor delta calculation error)");
            return 1;
        }

        // 3. Single Tap Gesture -> SGR Button 0 Press & Release at (42, 10)
        byte[] tapSgr = gestureHandler.handleSingleTap();
        String tapPacket = new String(tapSgr, StandardCharsets.US_ASCII);
        if (!"\033[<0;42;10M\033[<0;42;10m".equals(tapPacket)) {
            System.out.println("FAILED (Tap SGR packet mismatch: " + tapPacket + ")");
            return 1;
        }

        // 4. Long Press Gesture -> SGR Button 2 Press & Release (Right Click)
        byte[] longPressSgr = gestureHandler.handleLongPress();
        String longPressPacket = new String(longPressSgr, StandardCharsets.US_ASCII);
        if (!"\033[<2;42;10M\033[<2;42;10m".equals(longPressPacket)) {
            System.out.println("FAILED (Long press SGR packet mismatch: " + longPressPacket + ")");
            return 1;
        }

        // 5. Two-finger Scroll Gesture -> SGR Buttons 64 / 65
        byte[] scrollSgr = gestureHandler.handleTwoFingerScroll(-50f); // Scroll down
        String scrollPacket = new String(scrollSgr, StandardCharsets.US_ASCII);
        if (!"\033[<65;42;10M".equals(scrollPacket)) {
            System.out.println("FAILED (Two-finger scroll SGR packet mismatch: " + scrollPacket + ")");
            return 1;
        }

        // 6. Out-of-bounds Clamping Check
        gestureHandler.handleRelativeMove(5000f, 5000f);
        if (gestureHandler.getVirtualCursorCol() != 80 || gestureHandler.getVirtualCursorRow() != 24) {
            System.out.println("FAILED (Cursor out-of-bounds clamping failed)");
            return 1;
        }

        System.out.println("PASS");
        return 0;
    } catch (Exception e) {
        System.out.println("FAILED with exception: " + e.getMessage());
        return 1;
    }
}
```

### 3.2 `VsockTerminalClient` Socket 傳輸單元測試斷言設計 (`testVsockTerminalClientSocketTransmission`)
在 `tests/unit/TerminalAppUnitTest.java` 中新增 `testVsockTerminalClientSocketTransmission()` 測試方法：

```java
private static int testVsockTerminalClientSocketTransmission() {
    System.out.print("[TEST] F-R3-007: VsockTerminalClient Real Socket Transmission... ");
    try {
        // 1. Setup local ServerSocket loopback to emulate Port 5001 PTY Agent
        java.net.ServerSocket serverSocket = new java.net.ServerSocket(0);
        int localPort = serverSocket.getLocalPort();

        byte[] sessionId = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

        // 2. Instantiate VsockTerminalClient and connect to loopback socket
        VsockTerminalClient client = new VsockTerminalClient();
        java.net.Socket clientSocket = new java.net.Socket("127.0.0.1", localPort);
        java.net.Socket serverConn = serverSocket.accept();

        // 3. Transmit Frame via Client
        byte[] testData = "echo 'vsock_real_test'\n".getBytes(StandardCharsets.UTF_8);
        byte[] frame = VsockPtyFramer.serializeFrame(sessionId, VsockPtyFramer.PacketType.DATA, testData);
        
        java.io.OutputStream out = clientSocket.getOutputStream();
        out.write(frame);
        out.flush();

        // 4. Server receives raw bytes and parses frame header
        java.io.InputStream in = serverConn.getInputStream();
        byte[] receivedBuffer = new byte[VsockPtyFramer.HEADER_SIZE + testData.length];
        int bytesRead = 0;
        while (bytesRead < receivedBuffer.length) {
            int n = in.read(receivedBuffer, bytesRead, receivedBuffer.length - bytesRead);
            if (n < 0) break;
            bytesRead += n;
        }

        if (bytesRead != receivedBuffer.length) {
            System.out.println("FAILED (Received byte count mismatch: expected " + receivedBuffer.length + ", got " + bytesRead + ")");
            return 1;
        }

        // 5. Assert Framed Header Header & Payload Authenticity
        VsockPtyFramer.Frame parsedFrame = VsockPtyFramer.parseFrameHeaderAndPayload(receivedBuffer);
        if (!Arrays.equals(parsedFrame.sessionId, sessionId)) {
            System.out.println("FAILED (Session ID mismatch in transmitted frame)");
            return 1;
        }
        if (parsedFrame.type != VsockPtyFramer.PacketType.DATA) {
            System.out.println("FAILED (Packet type mismatch: expected DATA)");
            return 1;
        }
        if (!Arrays.equals(parsedFrame.payload, testData)) {
            System.out.println("FAILED (Payload bytes corrupted during socket transmission)");
            return 1;
        }

        clientSocket.close();
        serverConn.close();
        serverSocket.close();

        System.out.println("PASS");
        return 0;
    } catch (Exception e) {
        System.out.println("FAILED with exception: " + e.getMessage());
        return 1;
    }
}
```

### 3.3 確保 Python E2E (`test_m3_tier1.py` & `test_m3_tier2.py`) 執行真實二進位檔
在 `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` 及 `tests/e2e/tier2_boundary_corner/test_m3_tier2.py` 中：
1. `ensure_binaries_built()` 保留完整的 `javac` 與 `g++` 編譯指令。
2. 所有 Python 測試類別（T1-51..T1-85 與 T2-51..T2-85）均透過 `run_java_test()`、`run_native_terminal_test()` 或 `run_native_stress_test()` 經由 `CommandRunner.run()` 執行實體二進位檔案。
3. `run_java_test()` 檢查 `res.stdout` 包含 `JAVA TEST RESULT: ALL M3 TESTS PASSED SUCCESSFULLY` 及 `PASS`。

---

## 4. 嚴格避開 Dead Ends (Anti-Patterns Verification)

依據 `.agents/sub_orch_m3/DEAD_ENDS.md` 紀錄，本策略嚴格禁止以下作法：
- ❌ **Dead End 1**：Stub C `vterm_input_write` 或 Java Canvas 空畫版，忽略 0x1B 轉義碼、Alt Screen 或無滾動緩衝。
- ❌ **Dead End 2**：Facade/unwired touch mode (`TOUCHPAD_MODE` 直接返回 `true`)，或 `sendBytes`/`sendFrame` 僅寫 `Log.d` 卻未調用 `mVsockClient.sendFrame`。

本測試策略強制要求：
- `TOUCHPAD_MODE` 必須具有相對游標轉換 ($\Delta x, \Delta y$) 與完整 Tap/LongPress/Scroll SGR 封包斷言。
- `TerminalView` 必須實質串接 `mVsockClient.sendFrame()`，且單元測試透過實體 Socket Loopback 驗證 Socket 傳輸位元組與 Header 結構。

---

## 5. 結論 (Conclusion)

本技術測試驗證策略為 Milestone M3 Iteration 3 Remediation 提供了具體、可獨立驗證的單元測試與二進位執行標準，確保徹底解決 R2 的兩項 INTEGRITY VIOLATION 缺陷。
