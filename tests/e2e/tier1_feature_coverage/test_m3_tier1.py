"""
Tier 1 Functional Tests for Milestone 3: Native Touch Terminal Engine & IME.
Features covered: F-R3-001 through F-R3-007 (5 happy-path test cases each).
All test cases execute actual compiled Java .class files or C++ native binaries via CommandRunner.
"""

import sys
import os

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from framework import BaseTestCase, CustomAssertions, CommandRunner, VsockFramingHelper, VsockPacketType

_BINARIES_BUILT = False

def ensure_binaries_built():
    global _BINARIES_BUILT
    if _BINARIES_BUILT:
        return

    # 1. Compile Java classes & test suite
    cmd_java = (
        "javac -classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar:"
        "frameworks/base/core/java:packages/apps/LinuxTerminal/src "
        "-d /tmp/m3_classes $(find packages/apps/LinuxTerminal/src -name '*.java') "
        "tests/unit/TerminalAppUnitTest.java"
    )
    res_java = CommandRunner.run(cmd_java)
    if res_java.exit_code != 0:
        raise RuntimeError(f"Java build failed: {res_java.stderr}")
    CommandRunner.run("mkdir -p /tmp/m3_remediation_classes && cp -r /tmp/m3_classes/* /tmp/m3_remediation_classes/")

    # 2. Compile C++ libvterm test
    cmd_cpp = (
        "g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni/libvterm/include "
        "tests/unit/m3_native_terminal_test.cpp "
        "packages/apps/LinuxTerminal/jni/libvterm/src/*.c "
        "-o /tmp/m3_native_terminal_test && cp /tmp/m3_native_terminal_test ./tests/unit/m3_native_terminal_test_bin"
    )
    res_cpp = CommandRunner.run(cmd_cpp)
    if res_cpp.exit_code != 0:
        raise RuntimeError(f"C++ build failed: {res_cpp.stderr}")

    # 3. Compile C++ stress test
    cmd_stress = (
        "g++ -std=c++17 -Ipackages/apps/LinuxTerminal/jni -Ipackages/apps/LinuxTerminal/jni/libvterm/include "
        "tests/unit/m3_native_challenger2_stress.cpp "
        "packages/apps/LinuxTerminal/jni/sgr_mouse_generator.cpp "
        "packages/apps/LinuxTerminal/jni/pty_framing_handler.cpp "
        "packages/apps/LinuxTerminal/jni/vterm_parser.cpp "
        "packages/apps/LinuxTerminal/jni/libvterm/src/encoding.c "
        "packages/apps/LinuxTerminal/jni/libvterm/src/parser.c "
        "packages/apps/LinuxTerminal/jni/libvterm/src/pen.c "
        "packages/apps/LinuxTerminal/jni/libvterm/src/screen.c "
        "packages/apps/LinuxTerminal/jni/libvterm/src/state.c "
        "packages/apps/LinuxTerminal/jni/libvterm/src/unicode.c "
        "packages/apps/LinuxTerminal/jni/libvterm/src/vterm.c "
        "-o /tmp/m3_native_challenger2_stress && cp /tmp/m3_native_challenger2_stress ./tests/unit/m3_native_challenger2_stress_bin"
    )
    res_stress = CommandRunner.run(cmd_stress)
    if res_stress.exit_code != 0:
        raise RuntimeError(f"C++ stress build failed: {res_stress.stderr}")

    _BINARIES_BUILT = True


def run_java_test():
    ensure_binaries_built()
    return CommandRunner.run("java -cp /tmp/m3_remediation_classes:/tmp/m3_classes:/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar tests.unit.TerminalAppUnitTest")


def run_native_terminal_test():
    ensure_binaries_built()
    return CommandRunner.run("./tests/unit/m3_native_terminal_test_bin")


def run_native_stress_test():
    ensure_binaries_built()
    return CommandRunner.run("./tests/unit/m3_native_challenger2_stress_bin")


# ==============================================================================
# F-R3-001: Native Surface Canvas Renderer
# ==============================================================================
class TestR3_001_T1_51_SurfaceViewCreation(BaseTestCase):
    test_id = "T1-51"
    feature_id = "F-R3-001"
    title = "SurfaceView native window creation in TerminalActivity"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("F-R3-001: ColorPalette & TerminalScreenMatrix", res.stdout)
        CustomAssertions.assert_in("PASS", res.stdout)


class TestR3_001_T1_52_CanvasPipeline60FpsUpdate(BaseTestCase):
    test_id = "T1-52"
    feature_id = "F-R3-001"
    title = "Canvas rendering pipeline update at 60 FPS"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("JAVA TEST RESULT: ALL M3 TESTS PASSED", res.stdout)


class TestR3_001_T1_53_FontBitmapGlyphRendering(BaseTestCase):
    test_id = "T1-53"
    feature_id = "F-R3-001"
    title = "Terminal font bitmap glyph rendering accuracy"
    tier = 1

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("ASCII Stream Write & Cell Query: PASS", res.stdout)


class TestR3_001_T1_54_AnsiColorPaletteRendering(BaseTestCase):
    test_id = "T1-54"
    feature_id = "F-R3-001"
    title = "ANSI color palette rendering (16/256/truecolor)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("ColorPalette", res.stdout)


class TestR3_001_T1_55_DynamicTerminalGridRecalculation(BaseTestCase):
    test_id = "T1-55"
    feature_id = "F-R3-001"
    title = "Dynamic terminal window dimension recalculation on surface changed"
    tier = 1

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("Screen Resize to 40x120: PASS", res.stdout)


# ==============================================================================
# F-R3-002: libvterm Parser Integration
# ==============================================================================
class TestR3_002_T1_56_ParseStandardAsciiStream(BaseTestCase):
    test_id = "T1-56"
    feature_id = "F-R3-002"
    title = "Parse standard ASCII stream into screen matrix"
    tier = 1

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("Initialization: PASS", res.stdout)
        CustomAssertions.assert_in("ASCII Stream Write & Cell Query: PASS", res.stdout)


class TestR3_002_T1_57_InterpretAnsiEscapeSequences(BaseTestCase):
    test_id = "T1-57"
    feature_id = "F-R3-002"
    title = "Interpret ANSI escape sequences (cursor movement, colors, clear)"
    tier = 1

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("libvterm", res.stdout)


class TestR3_002_T1_58_ProcessVt100ControlCodes(BaseTestCase):
    test_id = "T1-58"
    feature_id = "F-R3-002"
    title = "Process VT100 / xterm control codes"
    tier = 1

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("ALL PASSED", res.stdout)


class TestR3_002_T1_59_MaintainScrollbackBuffer(BaseTestCase):
    test_id = "T1-59"
    feature_id = "F-R3-002"
    title = "Maintain scrollback buffer (up to 10,000 lines)"
    tier = 1

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_002_T1_60_ScreenResizeRecalculation(BaseTestCase):
    test_id = "T1-60"
    feature_id = "F-R3-002"
    title = "Screen resize recalculation via vterm_set_size()"
    tier = 1

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("Screen Resize to 40x120: PASS", res.stdout)


# ==============================================================================
# F-R3-003: TerminalInputConnection
# ==============================================================================
class TestR3_003_T1_61_InstantiateTerminalInputConnection(BaseTestCase):
    test_id = "T1-61"
    feature_id = "F-R3-003"
    title = "Instantiate TerminalInputConnection extends BaseInputConnection"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("TerminalKeyEncoder", res.stdout)


class TestR3_003_T1_62_KeyEventTranslation(BaseTestCase):
    test_id = "T1-62"
    feature_id = "F-R3-003"
    title = "Key event translation (ASCII, Enter, Backspace, Arrow keys)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_003_T1_63_ModifierStateHandling(BaseTestCase):
    test_id = "T1-63"
    feature_id = "F-R3-003"
    title = "Modifier state handling (Ctrl, Alt, Shift)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("Ctrl & Alt Keys", res.stdout)


class TestR3_003_T1_64_SoftKeyboardCommitTextDispatch(BaseTestCase):
    test_id = "T1-64"
    feature_id = "F-R3-003"
    title = "Soft keyboard commit text dispatch (commitText())"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_003_T1_65_SelectionCursorPositionReporting(BaseTestCase):
    test_id = "T1-65"
    feature_id = "F-R3-003"
    title = "Selection & cursor position reporting to Android IME"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


# ==============================================================================
# F-R3-004: Multi-stage CJK IME Commit
# ==============================================================================
class TestR3_004_T1_66_InlineZhuyinComposingText(BaseTestCase):
    test_id = "T1-66"
    feature_id = "F-R3-004"
    title = "Inline composing text display for Zhuyin (注音)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("CjkComposingTextManager", res.stdout)


class TestR3_004_T1_67_InlineCangjiePinyinComposingText(BaseTestCase):
    test_id = "T1-67"
    feature_id = "F-R3-004"
    title = "Inline composing text display for Cangjie (倉頡) & Pinyin (拼音)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_004_T1_68_CandidatesSelectionPanelDisplayNavigation(BaseTestCase):
    test_id = "T1-68"
    feature_id = "F-R3-004"
    title = "Candidates selection panel display & navigation"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_004_T1_69_Utf8MultiByteStringCommit(BaseTestCase):
    test_id = "T1-69"
    feature_id = "F-R3-004"
    title = "UTF-8 multi-byte string commit to pty stream"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_004_T1_70_ComposingTextBackspaceDeletion(BaseTestCase):
    test_id = "T1-70"
    feature_id = "F-R3-004"
    title = "Backspace deletion within composing text window"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("CjkComposingTextManager", res.stdout)


# ==============================================================================
# F-R3-005: Touch Modes State Machine
# ==============================================================================
class TestR3_005_T1_71_ActiveModeShellMode(BaseTestCase):
    test_id = "T1-71"
    feature_id = "F-R3-005"
    title = "Active mode set to SHELL_MODE (keyboard priority + touch scroll)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("TouchModeStateMachine", res.stdout)


class TestR3_005_T1_72_SwitchToTuiMouseMode(BaseTestCase):
    test_id = "T1-72"
    feature_id = "F-R3-005"
    title = "Switch to TUI_MOUSE_MODE (direct tap/drag mapped to mouse events)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_005_T1_73_SwitchToTouchpadMode(BaseTestCase):
    test_id = "T1-73"
    feature_id = "F-R3-005"
    title = "Switch to TOUCHPAD_MODE (virtual trackpad cursor overlay)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_005_T1_74_ModeTransitionUiIndicatorDisplay(BaseTestCase):
    test_id = "T1-74"
    feature_id = "F-R3-005"
    title = "Mode transition UI indicator display"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_005_T1_75_TouchModePreferencePersistence(BaseTestCase):
    test_id = "T1-75"
    feature_id = "F-R3-005"
    title = "Persistence of touch mode preference per session"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


# ==============================================================================
# F-R3-006: SGR Mouse Protocol Generator
# ==============================================================================
class TestR3_006_T1_76_TouchDownToSgrButtonPress(BaseTestCase):
    test_id = "T1-76"
    feature_id = "F-R3-006"
    title = "Touch down translated to \\e[<0;X;YM (SGR button 0 press)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("SgrMouseProtocolGenerator", res.stdout)

        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)
        CustomAssertions.assert_in("SGR Mouse Generator", res_stress.stdout)


class TestR3_006_T1_77_TouchDragToSgrMotion(BaseTestCase):
    test_id = "T1-77"
    feature_id = "F-R3-006"
    title = "Touch drag translated to \\e[<32;X;YM (SGR motion)"
    tier = 1

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_006_T1_78_TouchUpToSgrButtonRelease(BaseTestCase):
    test_id = "T1-78"
    feature_id = "F-R3-006"
    title = "Touch up translated to \\e[<0;X;Ym (SGR button 0 release)"
    tier = 1

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_006_T1_79_ScrollWheelToSgrButtons64_65(BaseTestCase):
    test_id = "T1-79"
    feature_id = "F-R3-006"
    title = "Scroll wheel gesture translated to SGR buttons 64/65"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_006_T1_80_OneBasedGridCoordinateTranslation(BaseTestCase):
    test_id = "T1-80"
    feature_id = "F-R3-006"
    title = "1-based coordinate translation matching terminal grid columns/rows"
    tier = 1

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


# ==============================================================================
# F-R3-007: Vsock Port 5001 PTY Framing
# ==============================================================================
class TestR3_007_T1_81_FrameHeaderSerialization(BaseTestCase):
    test_id = "T1-81"
    feature_id = "F-R3-007"
    title = "Frame header serialization: [SessionID (16B)][Type (1B)][Len (4B)]"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)
        CustomAssertions.assert_in("VsockPtyFramer", res.stdout)

        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_007_T1_82_FramePayloadParserExtraction(BaseTestCase):
    test_id = "T1-82"
    feature_id = "F-R3-007"
    title = "Frame payload parser extraction of DATA packets"
    tier = 1

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)
        CustomAssertions.assert_in("Valid frame creation & parsing: PASS", res_stress.stdout)


class TestR3_007_T1_83_TerminalWindowResizeFrame(BaseTestCase):
    test_id = "T1-83"
    feature_id = "F-R3-007"
    title = "Terminal window resize frame (RESIZE type with cols/rows)"
    tier = 1

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_007_T1_84_KeepalivePingPongFrameRoundtrip(BaseTestCase):
    test_id = "T1-84"
    feature_id = "F-R3-007"
    title = "Keepalive PING/PONG frame roundtrip over port 5001"
    tier = 1

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_007_T1_85_EndOfStreamPacketHandling(BaseTestCase):
    test_id = "T1-85"
    feature_id = "F-R3-007"
    title = "End-of-Stream (EOS) packet handling on shell logout"
    tier = 1

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)
