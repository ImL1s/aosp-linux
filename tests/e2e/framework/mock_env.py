"""
Environment definitions for AOSP Dual-OS E2E Test Suite.
Binds legacy MockEnvironment imports to SystemEnvironment backed by real sockets and inspectors.
"""

from .real_env import (
    SystemEnvironment,
    RealVsockBridge,
    RealSystemServerAdapter,
    RealSommelierAdapter,
    RealXdgPortalAdapter,
)

# Aliases for backwards compatibility with tests importing Mock names
MockEnvironment = SystemEnvironment
MockVsockBridge = RealVsockBridge
MockSystemServer = RealSystemServerAdapter
MockSommelier = RealSommelierAdapter
MockXdgPortal = RealXdgPortalAdapter
