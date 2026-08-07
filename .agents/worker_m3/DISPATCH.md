## 2026-08-06T10:59:36Z
You are Worker for Milestone M3 (Native Touch Terminal Engine, Custom InputConnection CJK IME, 3 Touch Modes State Machine, SGR Mouse Generator, Vsock Port 5001 PTY Framing).

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3

Mandatory Reference Files:
- ORIGINAL_REQUEST.md: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md (READ THIS FIRST!)
- PROJECT.md: /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
- SCOPE.md: /Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m3/SCOPE.md
- Technical Architecture Plan: /Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md
- Explorer 1 Analysis: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_1/analysis.md
- Explorer 2 Analysis: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_2/analysis.md
- Explorer 3 Analysis: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m3_3/analysis.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Scope & Task Requirements:
Implement all 7 features of Milestone M3 in packages/apps/LinuxTerminal/ (or packages/apps/TerminalApp/):
1. F-R3-001: Native Surface Canvas Renderer (`TerminalSurfaceView.java`, `terminal_renderer.cpp`, ANativeWindow lock/unlockAndPost, Dirty Rect local refresh, 60/120 FPS target, Monospace font grid, ANSI 16/256/TrueColor palette).
2. F-R3-002: libvterm Parser Integration (`VTermParserBridge.java`, `vterm_parser.cpp`, `libvterm_jni.cpp`, Alt screen `\e[?1049h`, 10000-line scrollback circular deque buffer, streaming UTF-8 partial byte buffering).
3. F-R3-003: TerminalInputConnection (`TerminalInputConnection.java`, `TerminalKeyEncoder.java` extending BaseInputConnection, full keycode translation for Backspace, Enter, Tab, Arrow Keys, F1-F12, Ctrl/Alt combinations).
4. F-R3-004: Multi-stage CJK IME Commit (`CjkComposingTextManager.java`, inline composing window, Bopomofo/Cangjie/Pinyin buffering, UTF-8 batch commit to PTY stream, InputMethodManager cursor anchor updates).
5. F-R3-005: Touch Modes State Machine (`TouchModeStateMachine.java` for SHELL_MODE, TUI_MOUSE_MODE, TOUCHPAD_MODE, DEC escape code auto-detection `\x1b[?1000h`/`\x1b[?1006h`, session preference persistence).
6. F-R3-006: SGR Mouse Protocol Generator (`SgrMouseProtocolGenerator.java` converting touch events/gestures to `\x1b[?<button>;<x>;<y>M`/`m`, 1-based grid coordinates, buttons 64/65 wheel scroll).
7. F-R3-007: Vsock Port 5001 PTY Framing (`VsockPtyFramer.java`, 21-byte binary packet header `[SessionID (16B)][Type (1B)][Length (4B)][Payload]`, RESIZE 4-byte payload `cols` and `rows`, partial header reassembly, 64KB max payload validation).

Also update Android.bp / C++ build configs and write comprehensive unit/integration test suites. Run local build and unit test commands to verify your implementation before reporting back.

Write your implementation report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m3/changes.md` and `handoff.md`, then send a concise message back.
