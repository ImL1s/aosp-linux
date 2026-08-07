# Dead Ends Log — Sub-Orchestrator M3

| Iteration | Approach Tried | Why It Failed | Files Touched |
|-----------|---------------|---------------|---------------|
| 1 | Stub C vterm_input_write & Java Canvas drawing | Ignored 0x1B escape codes, Alt Screen, 0-line scrollback buffer, JNI ANativeWindow missing | `vterm_parser.cpp`, `terminal_renderer.cpp`, `Android.bp` |
| 2 | Facade/unwired touch mode & vsock logging in Java View | TOUCHPAD_MODE returned true without relative motion tracking; sendBytes/sendFrame logged messages instead of calling mVsockClient.sendFrame | `TerminalView.java`, `TerminalSurfaceView.java` |
| 3 | Unhooked TouchpadController & broken UTF-8 feedBytes loop | TouchpadController ignored sgrGenerator parameter; feedBytes decremented validLen for CJK 3-byte sequences causing truncation & SIGABRT crash | `TouchpadController.java`, `vterm_parser.cpp`, `parser.c`, `TerminalInputConnection.java` |
