# Handoff Report — VsockTerminalClient Wiring Strategy (M3 Iteration 3 Remediation)

## 1. Observation
- **Observation 1 (Unwired Socket Send)**: In `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/TerminalView.java` (lines 95–111), `sendBytes()`, `sendFrame()`, and `sendResize()` serialize binary frames via `VsockPtyFramer` and log via `Log.d(...)`, but discard `frame` without calling `mVsockClient.sendFrame(frame)`.
- **Observation 2 (Uninvoked Socket Connect)**: In `TerminalView.java` (line 52), `mVsockClient` is instantiated, but `mVsockClient.connect(...)` is never invoked during View initialization or attachment lifecycle.
- **Observation 3 (Existing Client Method)**: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/net/VsockTerminalClient.java` (line 31) defines `public synchronized void connect(int guestCid, byte[] sessionId, TerminalStreamListener listener) throws IOException` and `public synchronized void sendFrame(byte[] frameBytes) throws IOException` (line 71).
- **Observation 4 (Reviewer Finding)**: `reviewer_m3_2_r2/review.md` Finding 2 explicitly flagged this as an Integrity Violation (Facade Implementation) because Logcat output simulates frame transmission without calling socket I/O APIs.
- **Observation 5 (Dead Ends Compliance)**: `DEAD_ENDS.md` Iteration 2 lists `sendBytes/sendFrame logged messages instead of calling mVsockClient.sendFrame` as a prohibited dead-end pattern.

## 2. Logic Chain
1. **From Observation 1 & 4**: Discarding `frame` after `Log.d` without sending over socket constitutes a facade implementation. To fix this, `sendBytes()`, `sendFrame()`, and `sendResize()` must wrap `mVsockClient.sendFrame(frame)` in `try-catch(IOException)` blocks and actually transmit the byte arrays over AF_VSOCK Port 5001.
2. **From Observation 2 & 3**: A socket client cannot transmit data if `connect()` is never called. Therefore, `TerminalView` must trigger `mVsockClient.connect(HOST_CID, mSessionId, listener)` during `onAttachedToWindow()` (or View init) and register a listener to route incoming PTY byte streams into `mVTermParser.writeInput(data)` followed by `postInvalidate()`.
3. **From Observation 2 & View Lifecycle**: To prevent resource leakage (dangling socket file descriptors / threads), `mVsockClient.close()` must be called in `onDetachedFromWindow()`.
4. **From Observation 5**: The remediation must strictly avoid pseudo-logging or swallowing socket exceptions without real transmission.

## 3. Caveats
- No caveats. All source files (`TerminalView.java`, `VsockTerminalClient.java`, `VsockPtyFramer.java`) and review findings have been thoroughly inspected.

## 4. Conclusion
Formulated technical remediation strategy for wiring `VsockTerminalClient` in `TerminalView.java`:
1. Connect `VsockTerminalClient` during View attachment (`onAttachedToWindow()`) to CID 2 (Port 5001) with `TerminalStreamListener` passing input to `mVTermParser.writeInput(data)` and calling `postInvalidate()`. Clean up socket via `mVsockClient.close()` in `onDetachedFromWindow()`.
2. Update `sendBytes()`, `sendFrame()`, and `sendResize()` in `TerminalView.java` to call `mVsockClient.sendFrame(frame)` directly, replacing facade logging with authentic AF_VSOCK socket writes.

## 5. Verification Method
1. **Java Source Compilation**:
   ```bash
   javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:frameworks/base/core/java:packages/apps/LinuxTerminal/src -d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name "*.java") tests/unit/TerminalAppUnitTest.java
   ```
2. **Unit Test Suite**:
   ```bash
   java -cp /tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest
   ```
3. **E2E Test Execution**:
   ```bash
   python3 tests/e2e/runner.py --filter F-R3
   ```
