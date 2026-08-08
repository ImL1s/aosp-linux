"""
E2E Test Framework Package for AOSP Dual-OS Project.
"""

from .base_test import BaseTestCase, TestResult, TestStatus
from .assertions import CustomAssertions
from .real_env import SystemEnvironment
from .socket_harness import RealVsockBridge, SocketHarnessServer, resolve_socket_path
from .system_inspector import (
    RealSystemServerInspector,
    RealWaylandInspector,
    RealDbusPortalInspector,
    BinaryInspector,
)
from .mock_env import (
    MockEnvironment,
    MockVsockBridge,
    MockSystemServer,
    MockSommelier,
    MockXdgPortal,
)
from .vsock_helper import VsockFramingHelper, VsockPacketType, HmacAuthHelper
from .command_runner import CommandRunner
from .report_formatter import ReportFormatter

__all__ = [
    "BaseTestCase",
    "TestResult",
    "TestStatus",
    "CustomAssertions",
    "SystemEnvironment",
    "RealVsockBridge",
    "SocketHarnessServer",
    "resolve_socket_path",
    "RealSystemServerInspector",
    "RealWaylandInspector",
    "RealDbusPortalInspector",
    "BinaryInspector",
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
