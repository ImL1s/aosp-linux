"""
Base Test Case class and Test Result structures for E2E Test Suite.
"""

import time
import traceback
from enum import Enum
from dataclasses import dataclass, field
from typing import Optional, Dict, Any, List

class TestStatus(Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    ERROR = "ERROR"
    SKIP = "SKIP"

@dataclass
class TestResult:
    test_id: str
    name: str
    feature_id: str
    tier: int
    status: TestStatus
    duration_sec: float
    error_message: Optional[str] = None
    stack_trace: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "test_id": self.test_id,
            "name": self.name,
            "feature_id": self.feature_id,
            "tier": self.tier,
            "status": self.status.value,
            "duration_sec": round(self.duration_sec, 4),
            "error_message": self.error_message,
            "stack_trace": self.stack_trace,
            "metadata": self.metadata,
        }

class BaseTestCase:
    """
    Abstract base class for all E2E test cases across Tiers 1-4.
    """

    test_id: str = "T-UNSET"
    feature_id: str = "F-UNSET"
    title: str = "Base Test Case"
    tier: int = 1

    def __init__(self, mock_env=None):
        self.mock_env = mock_env

    def setup(self):
        """Pre-test setup hook."""
        if self.mock_env:
            self.mock_env.reset()

    def teardown(self):
        """Post-test cleanup hook."""
        if self.mock_env and hasattr(self.mock_env, "reset"):
            self.mock_env.reset()

    def run_test(self):
        """Override this method in subclasses to execute test logic."""
        raise NotImplementedError("Subclasses must implement run_test()")

    def execute(self) -> TestResult:
        """Executes setup, run_test, and teardown with timing and exception handling."""
        start_time = time.time()
        status = TestStatus.PASS
        error_msg = None
        stack_trace = None

        try:
            self.setup()
            self.run_test()
        except AssertionError as e:
            status = TestStatus.FAIL
            error_msg = str(e)
            stack_trace = traceback.format_exc()
        except Exception as e:
            status = TestStatus.ERROR
            error_msg = f"{type(e).__name__}: {str(e)}"
            stack_trace = traceback.format_exc()
        finally:
            try:
                self.teardown()
            except Exception as teardown_err:
                if status == TestStatus.PASS:
                    status = TestStatus.ERROR
                    error_msg = f"Teardown error: {teardown_err}"

        duration = time.time() - start_time
        return TestResult(
            test_id=self.test_id,
            name=self.title,
            feature_id=self.feature_id,
            tier=self.tier,
            status=status,
            duration_sec=duration,
            error_message=error_msg,
            stack_trace=stack_trace,
        )
