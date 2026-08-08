# Scope: Milestone M3 (Real Vsock Socket Connect & Session ID - R3)

## Architecture
VsockTerminalClient -> AF_VSOCK syscall (Guest CID 3, Port 5001) -> VsockPtyFramer (16-byte session ID token header)
TerminalView -> ILinuxManager.createTerminalSession -> LinuxManagerService (16-byte token generation)

## Feature Inventory
| # | Feature | Description | Milestone | Status |
|---|---------|-------------|-----------|--------|
| 1 | AF_VSOCK Connect | Real socket connect to Guest CID 3 Port 5001 | M3 | DONE |
| 2 | Dynamic Session ID | Replace hardcoded ID with LinuxManagerService token | M3 | DONE |
| 3 | 16-byte Session Format | Format session tokens as session_%08d aligned with framer | M3 | DONE |

## Code Changes
- VsockTerminalClient.java: Added Os.connect(mSocketFd, address) targeting guestCid port 5001. Added pre-flight 16-byte session validation and clean socket teardown on error.
- TerminalView.java: Updated onAttachedToWindow to acquire dynamic 16-byte session ID from ILinuxManager.
- LinuxManagerService.java: Updated createTerminalSession to issue 16-byte session IDs (String.format(Locale.US, "session_%08d", ++mNextSessionId)).
