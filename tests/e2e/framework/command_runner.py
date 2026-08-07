"""
Command runner utility for invoking shell tools and target device commands.
"""

import subprocess
import time
from dataclasses import dataclass
from typing import Optional, List

@dataclass
class CommandResult:
    command: str
    exit_code: int
    stdout: str
    stderr: str
    duration_sec: float

class CommandRunner:
    @staticmethod
    def run(cmd: str, cwd: Optional[str] = None, timeout: float = 30.0) -> CommandResult:
        start_time = time.time()
        try:
            process = subprocess.run(
                cmd,
                shell=True,
                cwd=cwd,
                capture_output=True,
                text=True,
                timeout=timeout,
            )
            duration = time.time() - start_time
            return CommandResult(
                command=cmd,
                exit_code=process.returncode,
                stdout=process.stdout,
                stderr=process.stderr,
                duration_sec=duration,
            )
        except subprocess.TimeoutExpired as e:
            duration = time.time() - start_time
            return CommandResult(
                command=cmd,
                exit_code=-1,
                stdout=e.stdout.decode() if e.stdout else "",
                stderr=f"Command timed out after {timeout} seconds",
                duration_sec=duration,
            )
        except Exception as e:
            duration = time.time() - start_time
            return CommandResult(
                command=cmd,
                exit_code=-1,
                stdout="",
                stderr=str(e),
                duration_sec=duration,
            )
