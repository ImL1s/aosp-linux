# Handoff Report: Explorer 1 — Milestone M3 (F-R3-001 & F-R3-002)

**Working Directory**: `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_1`  
**Target Milestone**: Milestone M3 (Native Touch Terminal & IME)  
**Assigned Features**: F-R3-001 (Native Surface Canvas Renderer) & F-R3-002 (libvterm Parser Integration)  
**Date**: 2026-08-06  

---

## 1. Observation

1. **Existing Application Code**:
   - Application path: `/Users/iml1s/Documents/mine/aosp-linux/packages/apps/LinuxTerminal/`
   - Existing activity: `TerminalActivity.java` (Lines 1–50) sets up a basic layout with `TerminalView`.
   - Existing View: `TerminalView.java` (Lines 1–99) uses standard View `onDraw` with basic paint properties.
   - Build file: `packages/apps/LinuxTerminal/Android.bp` (Lines 1–19) defines the `LinuxTerminal` platform module linked against `android.system.linux`.

2. **Existing Test Infrastructure**:
   - `tests/e2e/tier1_feature_coverage/test_m3_tier1.py` defines functional test cases `T1-51` through `T1-60` for F-R3-001 and F-R3-002.
   - `tests/e2e/tier2_boundary_corner/test_m3_tier2.py` defines boundary test cases `T2-51` through `T2-60` for F-R3-001 and F-R3-002.
   - `tests/e2e/tier3_cross_feature/test_pairwise_matrix.py` defines cross-feature integration test `T3-PAIR-19` verifying libvterm screen updates trigger 60 FPS surface redraws.

3. **System Requirements & Architecture**:
   - Documented in `PROJECT.md` (Lines 53–54), `SCOPE.md` (Lines 11–13), and `aosp_linux_system_architecture_plan.md` (Lines 308–315).
   - High-throughput terminal parsing requires C99 `libvterm` integration to eliminate Java String/Regex GC overhead.
   - Low-latency touch input rendering requires a dedicated `SurfaceView` `RenderThread` aligned with `Choreographer` VSync.

---

## 2. Logic Chain

1. **Premise 1**: The legacy prototype used WebView + xterm.js which consumes ~150MB RAM and adds 45–90ms input latency due to IPC and DOM layout overhead.
2. **Premise 2**: A native `SurfaceView` with hardware acceleration off the main thread allows lock canvas rendering within a 16.66ms (60 FPS) or 8.33ms (120 FPS) frame budget, reducing rendering latency to <12ms.
3. **Premise 3**: Standard C99 `libvterm` provides an allocation-free VT100/xterm parser. Binding `libvterm` via JNI (`libvterm_jni.cpp`) allows raw PTY byte streams from vsock Port 5001 to be parsed efficiently directly into a 2D screen cell matrix.
4. **Premise 4**: Connecting `libvterm` damage callbacks to a double-buffered Java/C++ `TerminalScreenMatrix` enables dirty region updates (`surfaceHolder.lockCanvas(dirtyRect)`), minimizing redrawn pixels and CPU/GPU usage.
5. **Conclusion**: Designing `NativeSurfaceCanvasRenderer.java` alongside `VTermParser.java` and `libvterm_jni.cpp` fulfills all functional, performance, and memory constraints for F-R3-001 and F-R3-002.

---

## 3. Caveats

- **External C Library Packaging**: `libvterm` C sources (`libvterm/src/*.c` and `libvterm/include/*.h`) should be embedded under `packages/apps/LinuxTerminal/jni/libvterm/` or placed in `external/libvterm/` if shared across other platform native daemons.
- **Font Fallback**: On devices lacking built-in Monospace bold/italic glyph variants, `Paint.setTextSkewX()` and `Paint.setFakeBoldText()` are used as synthetic fallbacks.
- **CJK Font Metrics**: CJK ideographs require double-width grid math (`width = 2`). The renderer handles glyph advancement by $2 \times \text{cellWidth}$ for primary cells while skipping continuation dummy cells.

---

## 4. Conclusion

1. **F-R3-001 (Native Surface Canvas Renderer)**:
   - File path: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/renderer/NativeSurfaceCanvasRenderer.java`
   - Class hierarchy: Extends `SurfaceView`, implements `SurfaceHolder.Callback`, `Choreographer.FrameCallback`.
   - Core components: `TerminalSurfaceView`, `GlyphCache` (LRU bitmap cache), `ColorPalette` (16 ANSI / 256 xterm / 24-bit TrueColor), `TerminalScreenMatrix` (double-buffered cell grid).
   - Performance: Damaged bounding rect partial redrawing, 60/120 FPS VSync alignment, memory reclamation on view detachment.

2. **F-R3-002 (libvterm Parser Integration)**:
   - Java File path: `packages/apps/LinuxTerminal/src/com/android/virtualization/terminal/parser/VTermParser.java`
   - Native JNI path: `packages/apps/LinuxTerminal/jni/libvterm_jni.cpp`
   - Key JNI methods: `nativeInit`, `nativeWrite`, `nativeResize`, `nativeGetScreenMatrix`, `nativeDestroy`.
   - Parsing Features: 10,000-line ring scrollback buffer (`sb_pushline`), split multi-byte UTF-8 packet reassembly, malformed sequence resilience, alternate screen buffer switching (`\e[?1049h`).

3. **Build Target**:
   - Shared JNI library `libvterm_jni` compiled via `Android.bp` and linked into `LinuxTerminal` app package.

---

## 5. Verification Method

### 5.1 Independent Verification Commands
Run the following test commands from workspace root (`/Users/iml1s/Documents/mine/aosp-linux`):

```bash
# 1. Run Python E2E Test Suite for Milestone M3
python3 tests/e2e/runner.py --milestone M3

# 2. Verify Tier 1 Functional Tests (F-R3-001: T1-51..T1-55, F-R3-002: T1-56..T1-60)
python3 -m unittest tests/e2e/tier1_feature_coverage/test_m3_tier1.py

# 3. Verify Tier 2 Boundary Tests (F-R3-001: T2-51..T2-55, F-R3-002: T2-56..T2-60)
python3 -m unittest tests/e2e/tier2_boundary_corner/test_m3_tier2.py

# 4. Verify Tier 3 Pairwise Integration Test T3-PAIR-19
python3 -m unittest tests/e2e/tier3_cross_feature/test_pairwise_matrix.py
```

### 5.2 Invalidation Conditions
- Any frame drop or render time exceeding 16.66ms for $80 \times 24$ terminal matrix.
- Native parser crash or SIGSEGV when processing malformed ANSI escape sequences.
- Character corruption or buffer overflow when UTF-8 multi-byte sequences are split across vsock 5001 packet boundaries.
- Memory leak or unreleased global JNI reference after view detachment.
