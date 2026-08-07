#!/usr/bin/env python3
"""
Empirical Stress Test Harness by Challenger 2 for Milestone M3.
Focus: F-R3-005 (Touch Modes State Machine), F-R3-006 (SGR Mouse Generator), F-R3-007 (Vsock Port 5001 PTY Framing).
"""

import sys
import os
import time
import struct
import threading
import random

# Add parent directory to sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from e2e.framework import BaseTestCase, CustomAssertions, VsockFramingHelper, VsockPacketType

# ------------------------------------------------------------------------------
# Pure Python Re-implementations of Java/C++ logic matching exact repo code
# ------------------------------------------------------------------------------

class TouchMode:
    SHELL_MODE = "SHELL_MODE"
    TUI_MOUSE_MODE = "TUI_MOUSE_MODE"
    TOUCHPAD_MODE = "TOUCHPAD_MODE"

class TouchModeStateMachineRef:
    def __init__(self):
        self._current_mode = TouchMode.SHELL_MODE
        self._is_manual_locked = False
        self._mouse_tracking_requested = False
        self._lock = threading.Lock()
        self._listeners = []

    def get_current_mode(self):
        with self._lock:
            return self._current_mode

    def is_manual_locked(self):
        with self._lock:
            return self._is_manual_locked

    def set_manual_touch_mode(self, mode):
        with self._lock:
            self._is_manual_locked = True
            self._transition_to(mode, True)

    def unlock_auto_mode(self):
        with self._lock:
            self._is_manual_locked = False
            target = TouchMode.TUI_MOUSE_MODE if self._mouse_tracking_requested else TouchMode.SHELL_MODE
            self._transition_to(target, False)

    def on_terminal_escape_mouse_tracking_changed(self, enabled):
        with self._lock:
            self._mouse_tracking_requested = enabled
            if not self._is_manual_locked:
                target = TouchMode.TUI_MOUSE_MODE if enabled else TouchMode.SHELL_MODE
                self._transition_to(target, False)

    def _transition_to(self, new_mode, is_manual):
        if self._current_mode != new_mode:
            old_mode = self._current_mode
            self._current_mode = new_mode
            for listener in list(self._listeners):
                listener(old_mode, new_mode, is_manual)

    def add_listener(self, listener):
        self._listeners.append(listener)


class SgrMouseGeneratorRef:
    def __init__(self):
        self.mouse_tracking_enabled = False
        self.last_col = -1
        self.last_row = -1
        self.start_y = 0.0
        self.accumulated_scroll_y = 0.0

    def set_mouse_tracking_enabled(self, enabled):
        self.mouse_tracking_enabled = enabled

    @staticmethod
    def translate_pixel_to_grid(pixel_coord, cell_size, max_grid):
        if cell_size <= 0:
            cell_size = 1
        grid = int(pixel_coord / cell_size) + 1
        return max(1, min(grid, max_grid))

    def process_motion_event(self, action, x, y, pointer_count, cell_w, cell_h, cols, rows):
        if not self.mouse_tracking_enabled:
            return b""

        col = self.translate_pixel_to_grid(x, cell_w, cols)
        row = self.translate_pixel_to_grid(y, cell_h, rows)
        out = ""

        if action == "DOWN":
            self.last_col = col
            self.last_row = row
            self.start_y = y
            self.accumulated_scroll_y = 0.0
            out = f"\x1b[<0;{col};{row};M"
        elif action == "MOVE":
            if pointer_count == 1:
                if col != self.last_col or row != self.last_row:
                    self.last_col = col
                    self.last_row = row
                    out = f"\x1b[<32;{col};{row};M"
            elif pointer_count >= 2:
                dy = y - self.start_y
                self.start_y = y
                self.accumulated_scroll_y += dy
                if abs(self.accumulated_scroll_y) >= cell_h:
                    button = 65 if self.accumulated_scroll_y < 0 else 64
                    out = f"\x1b[<{button};{col};{row};M"
                    self.accumulated_scroll_y = 0.0
        elif action in ("UP", "CANCEL"):
            c = self.last_col if self.last_col > 0 else col
            r = self.last_row if self.last_row > 0 else row
            out = f"\x1b[<0;{c};{r};m"
            self.last_col = -1
            self.last_row = -1

        return out.encode("ascii")


class VsockPtyFramerRef:
    HEADER_SIZE = 21
    MAX_PAYLOAD_SIZE = 65536

    @staticmethod
    def parse_stream_java_sim(stream_bytes, expected_session_id=None):
        """Simulates VsockPtyFramer.java StreamParser implementation exactly."""
        buffer = bytearray(stream_bytes)
        parsed_frames = []
        errors = []
        read_offset = 0

        while len(buffer) - read_offset >= VsockPtyFramerRef.HEADER_SIZE:
            header = buffer[read_offset:read_offset + VsockPtyFramerRef.HEADER_SIZE]
            session_id = header[0:16]
            type_byte = header[16]
            
            # Java signed 32-bit int read
            payload_len_signed = struct.unpack(">i", header[17:21])[0]

            if payload_len_signed > VsockPtyFramerRef.MAX_PAYLOAD_SIZE:
                errors.append(f"PayloadLengthExceeded: {payload_len_signed}")
                # Java implementation resets buffer completely here!
                buffer = bytearray()
                break

            total_frame_len = VsockPtyFramerRef.HEADER_SIZE + payload_len_signed
            if total_frame_len < VsockPtyFramerRef.HEADER_SIZE or (len(buffer) - read_offset) < total_frame_len:
                # Fragmented or negative frame len
                if payload_len_signed < 0:
                    errors.append(f"NegativePayloadLengthException: {payload_len_signed}")
                    # Attempting slice with negative total_frame_len in Java causes exception
                    break
                break

            # Try parsing frame type byte
            valid_types = {1: "DATA", 2: "RESIZE", 3: "PING", 4: "PONG", 5: "EOS"}
            if type_byte not in valid_types:
                errors.append(f"InvalidFrameTypeByte: 0x{type_byte:02x}")
                # Java implementation advances read_offset by total_frame_len despite error!
                read_offset += total_frame_len
                continue

            payload = buffer[read_offset + VsockPtyFramerRef.HEADER_SIZE : read_offset + total_frame_len]
            if expected_session_id is None or session_id == expected_session_id:
                parsed_frames.append((session_id, valid_types[type_byte], payload))

            read_offset += total_frame_len

        return parsed_frames, errors


# ------------------------------------------------------------------------------
# Test Classes for Empirical Stress Testing
# ------------------------------------------------------------------------------

class TestM3_F05_RapidModeSwitchingStress(BaseTestCase):
    test_id = "STRESS-M3-01"
    feature_id = "F-R3-005"
    title = "Rapid Concurrent Touch Mode State Machine Transitions"
    tier = 2

    def run_test(self):
        sm = TouchModeStateMachineRef()
        errors = []
        transitions_count = [0]

        def listener(old, new, is_manual):
            transitions_count[0] += 1

        sm.add_listener(listener)

        def worker_func():
            modes = [TouchMode.SHELL_MODE, TouchMode.TUI_MOUSE_MODE, TouchMode.TOUCHPAD_MODE]
            for _ in range(1000):
                m = random.choice(modes)
                if random.random() < 0.5:
                    sm.set_manual_touch_mode(m)
                else:
                    sm.on_terminal_escape_mouse_tracking_changed(random.choice([True, False]))
                if random.random() < 0.2:
                    sm.unlock_auto_mode()

        threads = [threading.Thread(target=worker_func) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        CustomAssertions.assert_true(transitions_count[0] > 0, "Transitions should occur")
        print(f"       [Stress M3-01] Executed {transitions_count[0]} state transitions across 10 threads cleanly.")


class TestM3_F05_MidGestureModeSwitchBug(BaseTestCase):
    test_id = "STRESS-M3-02"
    feature_id = "F-R3-005"
    title = "State Desynchronization on Mid-Gesture Touch Mode Switch"
    tier = 2

    def run_test(self):
        generator = SgrMouseGeneratorRef()
        generator.set_mouse_tracking_enabled(True)

        # Step 1: Touch DOWN in TUI_MOUSE_MODE
        out_down = generator.process_motion_event("DOWN", 100, 100, 1, 10, 20, 80, 24)
        CustomAssertions.assert_equal(out_down, b"\x1b[<0;11;6;M")
        CustomAssertions.assert_equal(generator.last_col, 11)

        # Step 2: Mode switches to SHELL_MODE mid-gesture (disables mouse tracking)
        generator.set_mouse_tracking_enabled(False)

        # Step 3: Touch UP occurs while in SHELL_MODE (mouse tracking disabled)
        out_up_shell = generator.process_motion_event("UP", 100, 100, 1, 10, 20, 80, 24)
        CustomAssertions.assert_equal(out_up_shell, b"")

        # BUG CHECK: last_col and last_row were NOT reset to -1!
        is_bug_present = (generator.last_col != -1)
        if is_bug_present:
            print(f"       [BUG DETECTED] generator.last_col is still {generator.last_col} (stale state after mid-gesture mode switch)")
        
        # Step 4: Mode switches back to TUI_MOUSE_MODE
        generator.set_mouse_tracking_enabled(True)
        # Next move uses stale coordinates!
        out_move_stale = generator.process_motion_event("MOVE", 200, 200, 1, 10, 20, 80, 24)
        print(f"       [Stress M3-02 Output after re-enable]: {out_move_stale}")


class TestM3_F06_SgrMouseCoordinateLimits(BaseTestCase):
    test_id = "STRESS-M3-03"
    feature_id = "F-R3-006"
    title = "SGR Mouse Coordinate Clamping & Boundary Limits"
    tier = 2

    def run_test(self):
        # 1-based translation check
        c1 = SgrMouseGeneratorRef.translate_pixel_to_grid(0, 10, 80)
        CustomAssertions.assert_equal(c1, 1)

        c2 = SgrMouseGeneratorRef.translate_pixel_to_grid(-100, 10, 80)
        CustomAssertions.assert_equal(c2, 1)

        c3 = SgrMouseGeneratorRef.translate_pixel_to_grid(10000, 10, 80)
        CustomAssertions.assert_equal(c3, 80)

        # Check 4K display resolution (3840x2160)
        c4 = SgrMouseGeneratorRef.translate_pixel_to_grid(3839, 10, 80)
        CustomAssertions.assert_equal(c4, 80)


class TestM3_F06_SgrMouseScrollWheelQuantization(BaseTestCase):
    test_id = "STRESS-M3-04"
    feature_id = "F-R3-006"
    title = "SGR Mouse Scroll Wheel Delta Accumulation & Quantization Loss"
    tier = 2

    def run_test(self):
        gen = SgrMouseGeneratorRef()
        gen.set_mouse_tracking_enabled(True)

        # Touch DOWN
        gen.process_motion_event("DOWN", 50, 100, 2, 10, 20, 80, 24)

        # Fast swipe moving 100 pixels in single move (cell height = 20)
        # Move y from 100 to 200 (dy = +100 = 5 * cell_h)
        out_fast = gen.process_motion_event("MOVE", 50, 200, 2, 10, 20, 80, 24)
        
        # Generator emits only ONE scroll event (\x1b[<64;6;11;M) and resets accumulated scroll to 0!
        print(f"       [Stress M3-04] Fast swipe (100px = 5 cells) emitted output: {out_fast}")
        # Notice: only 1 scroll event emitted instead of 5 scroll ticks! Quantization loss confirmed!


class TestM3_F07_VsockFramingNegativePayloadLengthBypass(BaseTestCase):
    test_id = "STRESS-M3-05"
    feature_id = "F-R3-007"
    title = "Vsock Port 5001 Negative Payload Length Bypass Vulnerability"
    tier = 2

    def run_test(self):
        session_id = b"0123456789abcdef"
        type_byte = 1 # DATA
        # Payload length set to 0xFFFFFFFF (-1 in signed 32-bit int)
        payload_len_bytes = struct.pack(">i", -1)
        
        corrupted_header = session_id + bytes([type_byte]) + payload_len_bytes
        
        frames, errors = VsockPtyFramerRef.parse_stream_java_sim(corrupted_header, session_id)
        
        print(f"       [Stress M3-05 Result] Errors caught: {errors}")
        CustomAssertions.assert_true(len(errors) > 0, "Negative payload length must be detected as an error")
        CustomAssertions.assert_true("NegativePayloadLengthException" in errors[0] or "PayloadLengthExceeded" in errors[0], 
                                     "Must catch negative payload length")


class TestM3_F07_VsockFramingInvalidTypeByteDesync(BaseTestCase):
    test_id = "STRESS-M3-06"
    feature_id = "F-R3-007"
    title = "Vsock Port 5001 Invalid Type Byte Stream Desynchronization"
    tier = 2

    def run_test(self):
        session_id = b"0123456789abcdef"
        invalid_type_byte = 0x99 # Unknown type byte
        payload_len = 10
        payload = b"0123456789"
        
        bad_frame = session_id + bytes([invalid_type_byte]) + struct.pack(">I", payload_len) + payload
        good_frame = session_id + bytes([1]) + struct.pack(">I", 5) + b"hello"
        
        stream = bad_frame + good_frame
        
        frames, errors = VsockPtyFramerRef.parse_stream_java_sim(stream, session_id)
        
        print(f"       [Stress M3-06 Result] Parsed frames after bad frame: {len(frames)}, Errors: {errors}")


def main():
    print("=" * 80)
    print("      CHALLENGER 2 EMPIRICAL STRESS TEST SUITE (MILESTONE M3)")
    print("=" * 80)
    
    tests = [
        TestM3_F05_RapidModeSwitchingStress(),
        TestM3_F05_MidGestureModeSwitchBug(),
        TestM3_F06_SgrMouseCoordinateLimits(),
        TestM3_F06_SgrMouseScrollWheelQuantization(),
        TestM3_F07_VsockFramingNegativePayloadLengthBypass(),
        TestM3_F07_VsockFramingInvalidTypeByteDesync(),
    ]
    
    passed = 0
    failed = 0
    
    for test in tests:
        res = test.execute()
        status = "[PASS]" if res.status.name == "PASS" else "[FAIL]"
        print(f"{status} {res.test_id:<14} | {res.feature_id:<10} | {res.name}")
        if res.status.name == "PASS":
            passed += 1
        else:
            failed += 1
            print(f"       └── Failure: {res.error_message}")
            
    print("-" * 80)
    print(f"Total: {len(tests)} | Passed: {passed} | Failed: {failed}")
    print("=" * 80)

if __name__ == "__main__":
    main()
