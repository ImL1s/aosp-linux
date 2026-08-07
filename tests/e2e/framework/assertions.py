"""
Custom assertion helpers for E2E Test Framework.
"""

from typing import Any, Callable, Type

class CustomAssertions:
    @staticmethod
    def assert_equal(actual: Any, expected: Any, msg: str = None):
        if actual != expected:
            default_msg = f"Expected {expected!r}, but got {actual!r}"
            raise AssertionError(f"{msg}: {default_msg}" if msg else default_msg)

    @staticmethod
    def assert_not_equal(actual: Any, expected: Any, msg: str = None):
        if actual == expected:
            default_msg = f"Expected value not equal to {expected!r}"
            raise AssertionError(f"{msg}: {default_msg}" if msg else default_msg)

    @staticmethod
    def assert_true(expr: bool, msg: str = None):
        if not expr:
            raise AssertionError(msg or "Expression evaluated to False, expected True")

    @staticmethod
    def assert_false(expr: bool, msg: str = None):
        if expr:
            raise AssertionError(msg or "Expression evaluated to True, expected False")

    @staticmethod
    def assert_in(item: Any, container: Any, msg: str = None):
        if item not in container:
            raise AssertionError(msg or f"Item {item!r} not found in container")

    @staticmethod
    def assert_raises(exception_cls: Type[Exception], fn: Callable, *args, **kwargs):
        try:
            fn(*args, **kwargs)
        except exception_cls:
            return
        except Exception as e:
            raise AssertionError(
                f"Expected exception {exception_cls.__name__}, but raised {type(e).__name__}: {e}"
            )
        raise AssertionError(f"Expected exception {exception_cls.__name__}, but no exception was raised")

    @staticmethod
    def assert_vsock_frame(header: bytes, expected_session_id: bytes, expected_type: int):
        if len(header) < 21:
            raise AssertionError(f"Header length {len(header)} < minimum 21 bytes")
        session_id = header[0:16]
        pkg_type = header[16]
        if session_id != expected_session_id:
            raise AssertionError(f"Session ID mismatch: {session_id.hex()} != {expected_session_id.hex()}")
        if pkg_type != expected_type:
            raise AssertionError(f"Packet type mismatch: {pkg_type} != {expected_type}")

    @staticmethod
    def assert_hmac_valid(signature: bytes, expected_signature: bytes):
        if signature != expected_signature:
            raise AssertionError(f"HMAC authentication failed. Got {signature.hex()}, expected {expected_signature.hex()}")

    @staticmethod
    def assert_selinux_denial(audit_logs: list, domain: str, target: str):
        found = any(domain in log and target in log and "denied" in log for log in audit_logs)
        if not found:
            raise AssertionError(f"No SELinux AVC denial found in logs for scontext={domain} tcontext={target}")
