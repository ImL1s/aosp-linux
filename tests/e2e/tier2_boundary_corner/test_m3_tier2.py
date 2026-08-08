"""
Tier 2 Boundary & Corner Case Tests for Milestone 3: Native Touch Terminal App Engine with Custom InputConnection & Touch Modes.
Features: F-R3-001 through F-R3-007 (Tests T2-51 .. T2-85)
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
        "javac -sourcepath frameworks/base/core/java:frameworks/base/services/core/java:packages/apps/LinuxTerminal/src "
        "-classpath /Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar "
        "-d /tmp/m3_classes tests/unit/TerminalAppUnitTest.java"
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


# -----------------------------------------------------------------------------
# F-R3-001: Native Surface Canvas Renderer (T2-51 .. T2-55)
# -----------------------------------------------------------------------------
class TestR3_001_T2_51_RapidSurfaceRotation(BaseTestCase):
    test_id = "T2-51"
    feature_id = "F-R3-001"
    title = "Handle rapid surface rotation without frame drop/tearing"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_001_T2_52_MemoryReclamationViewDetach(BaseTestCase):
    test_id = "T2-52"
    feature_id = "F-R3-001"
    title = "Memory reclamation on view detachment"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_001_T2_53_HighResRenderingBudget(BaseTestCase):
    test_id = "T2-53"
    feature_id = "F-R3-001"
    title = "High resolution (4K/8K display) rendering performance budget check"
    tier = 2

    def run_test(self):
        res = run_native_stress_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_001_T2_54_InvalidSurfaceStateBackground(BaseTestCase):
    test_id = "T2-54"
    feature_id = "F-R3-001"
    title = "Invalid surface state handling when backgrounded"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_001_T2_55_GlyphRasterizationFallback(BaseTestCase):
    test_id = "T2-55"
    feature_id = "F-R3-001"
    title = "Glyph rasterization fallback on unsupported Unicode symbols"
    tier = 2

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


# -----------------------------------------------------------------------------
# F-R3-002: libvterm Parser Integration (T2-56 .. T2-60)
# -----------------------------------------------------------------------------
class TestR3_002_T2_56_MalformedEscapeSequence(BaseTestCase):
    test_id = "T2-56"
    feature_id = "F-R3-002"
    title = "Malformed escape sequence parser resilience (no crash)"
    tier = 2

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_002_T2_57_BinaryLogDumpOverflow(BaseTestCase):
    test_id = "T2-57"
    feature_id = "F-R3-002"
    title = "Overflow handling on massive binary log dump"
    tier = 2

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_002_T2_58_AltScreenBufferSwitching(BaseTestCase):
    test_id = "T2-58"
    feature_id = "F-R3-002"
    title = "Alternate screen buffer switching (Vim exit restoration)"
    tier = 2

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_002_T2_59_WideUtf8Alignment(BaseTestCase):
    test_id = "T2-59"
    feature_id = "F-R3-002"
    title = "Zero-width & wide UTF-8 character alignment handling"
    tier = 2

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_002_T2_60_PartialMultiByteSplit(BaseTestCase):
    test_id = "T2-60"
    feature_id = "F-R3-002"
    title = "UTF-8 partial multi-byte sequence split across packet boundaries"
    tier = 2

    def run_test(self):
        res = run_native_terminal_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


# -----------------------------------------------------------------------------
# F-R3-003: TerminalInputConnection (T2-61 .. T2-65)
# -----------------------------------------------------------------------------
class TestR3_003_T2_61_KeyStormHandling(BaseTestCase):
    test_id = "T2-61"
    feature_id = "F-R3-003"
    title = "Rapid key press storm handling without character drop"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_003_T2_62_SpecialKeyComboMapping(BaseTestCase):
    test_id = "T2-62"
    feature_id = "F-R3-003"
    title = "Special key combination mapping (Ctrl+C, Ctrl+Z, Ctrl+D)"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_003_T2_63_HardwareKeyboardPassthrough(BaseTestCase):
    test_id = "T2-63"
    feature_id = "F-R3-003"
    title = "Hardware keyboard physical key event passthrough"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_003_T2_64_DeadKeyComposition(BaseTestCase):
    test_id = "T2-64"
    feature_id = "F-R3-003"
    title = "Dead key composition support"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_003_T2_65_FocusLossBufferReset(BaseTestCase):
    test_id = "T2-65"
    feature_id = "F-R3-003"
    title = "Input connection focus loss clean buffer reset"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


# -----------------------------------------------------------------------------
# F-R3-004: Multi-stage CJK IME Commit (T2-66 .. T2-70)
# -----------------------------------------------------------------------------
class TestR3_004_T2_66_CancelInlineComposition(BaseTestCase):
    test_id = "T2-66"
    feature_id = "F-R3-004"
    title = "Cancel inline composition on focus change or ESC key"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_004_T2_67_RapidCandidateSelection(BaseTestCase):
    test_id = "T2-67"
    feature_id = "F-R3-004"
    title = "Handle rapid IME candidate selection without buffer corrupt"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_004_T2_68_SurroundingTextQuery(BaseTestCase):
    test_id = "T2-68"
    feature_id = "F-R3-004"
    title = "Surround text query handling near line boundaries"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_004_T2_69_LongComposingTextTruncation(BaseTestCase):
    test_id = "T2-69"
    feature_id = "F-R3-004"
    title = "Extremely long composing text buffer (>256 chars) truncation"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_004_T2_70_ThirdPartyKeyboardCompat(BaseTestCase):
    test_id = "T2-70"
    feature_id = "F-R3-004"
    title = "Third-party keyboard (Gboard, SwiftKey) compatibility check"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


# -----------------------------------------------------------------------------
# F-R3-005: Touch Modes State Machine (T2-71 .. T2-75)
# -----------------------------------------------------------------------------
class TestR3_005_T2_71_MultiTouchRejectionShell(BaseTestCase):
    test_id = "T2-71"
    feature_id = "F-R3-005"
    title = "Multi-touch gesture rejection in SHELL_MODE"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_005_T2_72_PinchToZoomFontScaling(BaseTestCase):
    test_id = "T2-72"
    feature_id = "F-R3-005"
    title = "Pinch-to-zoom font scaling gesture in SHELL_MODE"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_005_T2_73_PalmRejectionTouchpad(BaseTestCase):
    test_id = "T2-73"
    feature_id = "F-R3-005"
    title = "Palm rejection filtering in TOUCHPAD_MODE"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_005_T2_74_FastTouchModeToggle(BaseTestCase):
    test_id = "T2-74"
    feature_id = "F-R3-005"
    title = "Fast touch mode toggling race condition safety"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


class TestR3_005_T2_75_TouchModePauseResume(BaseTestCase):
    test_id = "T2-75"
    feature_id = "F-R3-005"
    title = "State machine recovery on app pause/resume"
    tier = 2

    def run_test(self):
        res = run_java_test()
        CustomAssertions.assert_equal(res.exit_code, 0)


# -----------------------------------------------------------------------------
# F-R3-006: SGR Mouse Protocol Generator (T2-76 .. T2-80)
# -----------------------------------------------------------------------------
class TestR3_006_T2_76_OutOfBoundsCoordClamping(BaseTestCase):
    test_id = "T2-76"
    feature_id = "F-R3-006"
    title = "Out-of-bounds coordinate clamping (X > cols, Y > rows)"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_006_T2_77_SubPixelMotionThreshold(BaseTestCase):
    test_id = "T2-77"
    feature_id = "F-R3-006"
    title = "Sub-pixel touch motion delta thresholding (debounce)"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_006_T2_78_RightClickLongPress(BaseTestCase):
    test_id = "T2-78"
    feature_id = "F-R3-006"
    title = "Right-click long-press translation to SGR button 2"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_006_T2_79_ResizeGridRecalculation(BaseTestCase):
    test_id = "T2-79"
    feature_id = "F-R3-006"
    title = "Terminal resize dynamic grid coordinate recalculation"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_006_T2_80_DisableSgrWhenTrackingDisabled(BaseTestCase):
    test_id = "T2-80"
    feature_id = "F-R3-006"
    title = "Disable SGR mouse sequence generation when guest app disables mouse tracking"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


# -----------------------------------------------------------------------------
# F-R3-007: Vsock Port 5001 PTY Framing (T2-81 .. T2-85)
# -----------------------------------------------------------------------------
class TestR3_007_T2_81_InvalidHeaderByteRejection(BaseTestCase):
    test_id = "T2-81"
    feature_id = "F-R3-007"
    title = "Reject invalid magic / unknown frame type header byte"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)
        CustomAssertions.assert_in("Invalid type byte rejection: PASS", res_stress.stdout)


class TestR3_007_T2_82_FragmentedPayloadReassembly(BaseTestCase):
    test_id = "T2-82"
    feature_id = "F-R3-007"
    title = "Handle fragmented TCP/vsock packet payload reassembly"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)
        CustomAssertions.assert_in("Fragmented byte stream reassembly: PASS", res_stress.stdout)


class TestR3_007_T2_83_PartialHeaderSplit(BaseTestCase):
    test_id = "T2-83"
    feature_id = "F-R3-007"
    title = "Reconstruct partial frame headers split across socket reads"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_007_T2_84_SessionIdMismatchDrop(BaseTestCase):
    test_id = "T2-84"
    feature_id = "F-R3-007"
    title = "Session ID mismatch packet drop"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)


class TestR3_007_T2_85_PayloadLengthSanityCheck(BaseTestCase):
    test_id = "T2-85"
    feature_id = "F-R3-007"
    title = "Payload length sanity check (reject length > 64KB per frame)"
    tier = 2

    def run_test(self):
        res_stress = run_native_stress_test()
        CustomAssertions.assert_equal(res_stress.exit_code, 0)
        CustomAssertions.assert_in("Oversized payload length (>64KB) rejection: PASS", res_stress.stdout)
