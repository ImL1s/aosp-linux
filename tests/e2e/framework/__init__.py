"""
E2E Test Framework Package for AOSP Dual-OS Project.
"""

from .base_test import BaseTestCase, TestResult, TestStatus
from .assertions import CustomAssertions
from .mock_env import MockEnvironment, MockVsockBridge, MockSystemServer, MockSommelier, MockXdgPortal
from .vsock_helper import VsockFramingHelper, VsockPacketType, HmacAuthHelper
from .command_runner import CommandRunner
from .report_formatter import ReportFormatter

__all__ = [
    "BaseTestCase",
    "TestResult",
    "TestStatus",
    "CustomAssertions",
    "MockEnvironment",
    "MockVsockBridge",
    "MockSystemServer",
    "MockSommelier",
    "MockXdgPortal",
    "VsockFramingHelper",
    "VsockPacketType",
    "HmacAuthHelper",
    "CommandRunner",
    "ReportFormatter",
]
