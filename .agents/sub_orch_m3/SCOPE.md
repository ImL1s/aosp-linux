# Scope: Milestone M3 (Native Touch Terminal & IME) (`SCOPE.md`)

## Executive Summary
Milestone M3 implements the Native Touch Terminal Engine, Custom InputConnection for CJK IME (Zhuyin/Cangjie/Pinyin inline composing window & UTF-8 commit), 3 Touch Modes State Machine (Shell Mode, TUI Mouse Mode, Touchpad Mode), SGR Mouse Protocol Generator for Vim/tmux, libvterm parser integration, and Vsock Port 5001 PTY Framing in `packages/apps/TerminalApp/` (and `LinuxTerminal/`). Status: **DONE**

---

## Feature Inventory for Milestone M3

| # | Feature ID | Feature Name | Description | Source | Status |
|---|------------|--------------|-------------|--------|--------|
| 1 | F-R3-001 | Native Surface Canvas Renderer | Low-latency Android Native Canvas Surface renderer for terminal | PROJECT.md | DONE |
| 2 | F-R3-002 | libvterm Parser Integration | C/C++ `libvterm` / `vte` state parser integration via JNI | PROJECT.md | DONE |
| 3 | F-R3-003 | TerminalInputConnection | Custom `TerminalInputConnection extends BaseInputConnection` | PROJECT.md | DONE |
| 4 | F-R3-004 | Multi-stage CJK IME Commit | Zhuyin / Cangjie / Pinyin inline composing window & UTF-8 commit pipeline | PROJECT.md | DONE |
| 5 | F-R3-005 | Touch Modes State Machine | State machine for Shell Mode, TUI Mouse Mode, and Touchpad Mode | PROJECT.md | DONE |
| 6 | F-R3-006 | SGR Mouse Protocol Generator | Touch-to-SGR mouse protocol packet translation for Vim / tmux | PROJECT.md | DONE |
| 7 | F-R3-007 | Vsock Port 5001 PTY Framing | Framing header parser and byte stream serializer over Vsock 5001 | PROJECT.md | DONE |

---

## Work Items & Sub-tasks
1. **Explore & Strategy**: Analyze existing repository files under `packages/apps/TerminalApp/`, `frameworks/base/`, or native libraries, and design architectural blueprint. [DONE]
2. **Worker Implementation**:
   - `NativeSurfaceCanvasRenderer.java` / Native SurfaceView renderer component. [DONE]
   - `VTermParser.cpp` / JNI bridge to `libvterm`. [DONE]
   - `TerminalInputConnection.java` for custom IME input handling with composing window and CJK UTF-8 commit. [DONE]
   - `TouchModeStateMachine.java` managing Shell, TUI Mouse, and Touchpad modes. [DONE]
   - `SgrMouseProtocolGenerator.java` converting touch events to SGR mouse escape sequences (`\033[?<button>;<x>;<y>M`). [DONE]
   - `TouchpadController.java` handling relative touch tracking, tap, long press, two-finger scroll. [DONE]
   - `VsockTerminalClient.java` & `VsockPtyFramer.java` implementing `[SessionID (16B)][Type (1B)][Length (4B)][Payload]` protocol over vsock port 5001. [DONE]
   - Unit and integration tests for all components. [DONE]
3. **Review & Challenge**: Verify code quality, performance, unit test coverage, edge cases, and empirical stress test robustness. [DONE - ALL APPROVE]
4. **Forensic Integrity Verification**: Verify implementation authenticity, zero cheating/hardcoding/facades. [DONE - CLEAN]

---

## Code Layout
`packages/apps/LinuxTerminal/`
- `src/com/android/virtualization/terminal/TerminalActivity.java`
- `src/com/android/virtualization/terminal/TerminalView.java`
- `src/com/android/virtualization/terminal/renderer/TerminalSurfaceView.java`
- `src/com/android/virtualization/terminal/parser/VTermParser.java` / `jni/libvterm_jni.cpp`
- `src/com/android/virtualization/terminal/ime/TerminalInputConnection.java`
- `src/com/android/virtualization/terminal/ime/CjkComposingTextManager.java`
- `src/com/android/virtualization/terminal/touch/TouchModeStateMachine.java`
- `src/com/android/virtualization/terminal/touch/TouchpadController.java`
- `src/com/android/virtualization/terminal/touch/SgrMouseProtocolGenerator.java`
- `src/com/android/virtualization/terminal/net/VsockTerminalClient.java`
- `src/com/android/virtualization/terminal/net/VsockPtyFramer.java`
- `tests/unit/TerminalAppUnitTest.java`
- `tests/unit/TouchpadVsockStressTest.java`
