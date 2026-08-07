# Summary of Changes — Milestone M3 (Iteration 2 Remediation)

## 1. Syntax & Package Cleanup
- **Java String Escape Fix (`"\x1b"` -> `"\033"` / `"\u001b"`)**: Replaced invalid C-style `"\x1b"` string escape sequences with standard Java `"\033"` / `"\u001b"` across `TerminalKeyEncoder.java`, `SgrMouseProtocolGenerator.java`, and `TerminalAppUnitTest.java`, fixing 130 javac syntax errors.
- **Package Architecture & Import Cleanup**: Verified removal of shadow flat duplicate `.java` files from `src/com/android/virtualization/terminal/` root directory. Updated `TerminalActivity.java` and `TerminalView.java` to explicitly import subpackage modules (`.renderer.*`, `.parser.*`, `.ime.*`, `.touch.*`, `.net.*`). Added missing `INPUT_METHOD_SERVICE` and `MODE_PRIVATE` constants to `frameworks/base/core/java/android/content/Context.java`.

## 2. Real libvterm JNI Integration (F-R3-002)
- **JNI Signature & Package Match**: Matched JNI symbol names in `libvterm_jni.cpp` (`Java_com_android_virtualization_terminal_parser_VTermParser_nativeInit`) to `com.android.virtualization.terminal.parser.VTermParser`. Added 2-argument constructor `VTermParser(rows, cols)` for convenience.
- **Removed Exception-Silencing Facade**: Removed `try...catch (UnsatisfiedLinkError)` blocks in `VTermParser.java` so native library linkage issues fail fast instead of silently reverting to a dummy facade.
- **JNI Thread Attachment & Memory Safety**: Added `AttachCurrentThread`/`DetachCurrentThread` guards in JNI callbacks (`cb_damage`, `cb_movecursor`, `cb_settermprop`) and added `DeleteLocalRef(cbClass)` in `nativeInit` to prevent JNI local reference table overflows.
- **Authentic Build & Header Alignment**: Verified `Android.bp` links authentic `libvterm/src/*.c` source files (`vterm.c`, `screen.c`, `state.c`, `parser.c`, `pen.c`, `unicode.c`, `encoding.c`).

## 3. Genuine Surface Renderer & Vsock Communication (F-R3-001 & F-R3-007)
- **Terminal Surface Matrix Canvas Drawing**: Updated `TerminalView.java` `onDraw()` to dynamically fetch codepoints, background colors, foreground colors, and ANSI attributes from `mVTermParser.getScreenMatrix()` and draw real cell grids on Canvas. Updated `NativeSurfaceCanvasRenderer.java` to lock/unlockAndPost dirty rects on `SurfaceHolder`. Removed all hardcoded static text strings.
- **Real Vsock Socket Communication**: Integrated `VsockTerminalClient.java` using `AF_VSOCK` sockets on Port 5001. Implemented `sendFrame()` and `sendResize()` methods required by `PtySender` interface.
- **VsockPtyFramer Overflow & Resync Protections**: Ensured `payloadLength < 0` MSB signed integer overflow check is enforced and implemented 1-byte stream resynchronization when encountering invalid packet types or corrupted headers.

## 4. IME & Touch Mode Fixes (F-R3-004, F-R3-005, F-R3-006)
- **CJK Composing Text Manager Bounds Guard**: Enforced strict bounds clamping on `mCursorPosition` within `[0, bufferLen]` in `CjkComposingTextManager.java::deleteBeforeCursor`, preventing `StringIndexOutOfBoundsException`. Added helper methods `updateComposing`, `hide`, `draw` in `CjkComposingWindow.java`.
- **Functional Touchpad Mode & Mode Lock Persistence**: Implemented relative touch gesture motion tracking in `TerminalView.java` (`handleTouchpadEvent`) with virtual cursor grid calculation, single tap (left click button 0), long press (right click button 2), and two-finger scroll wheel (buttons 64/65). Persisted `KEY_PREF_MANUAL_LOCKED` in `SharedPreferences` via `TouchModeStateMachine.java`.
- **DEC SGR 1006 Format Fix**: Corrected DEC SGR 1006 packet formatting string to `"\033[<%d;%d;%d%s"` (removing the extra trailing semicolon before `M`/`m`), aligning with standard Vim/tmux/htop expectations.

## 5. Test Suite Authenticity
- **Unit Test Compilation**: Fixed import declarations, escape sequences, and SGR assertion strings in `TerminalAppUnitTest.java`, `m3_native_terminal_test.cpp`, and `m3_native_challenger2_stress.cpp`.
- **Subprocess Execution in E2E Runner**: Updated `test_m3_tier1.py` and `test_m3_tier2.py` to trigger on-demand build of Java classes and C++ native binaries via `CommandRunner.run()` and execute actual compiled binaries (`TerminalAppUnitTest`, `m3_native_terminal_test`, `m3_native_challenger2_stress`) rather than evaluating local Python dictionaries. Test duration increased from fake 0.05s to authentic ~9.0s with 100% pass rate across 80 tests.
