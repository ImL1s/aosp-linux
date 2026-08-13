## 2026-08-13T17:20:39Z

<USER_REQUEST>
You are the Project Orchestrator for the AOSP Dual-OS Project.
Working directory: /Users/iml1s/Documents/mine/aosp-linux
Your working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/orchestrator
Original Request File: /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md

Please read `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md` (specifically the latest follow-up section) and execute the remediation task:
1. R1: Complete Java Syntax & Compilation Closure — Fix syntax error in `LinuxAppProxyActivity.java` (duplicate unclosed `attachSurfaceControlToBridge` method declarations) and ensure all AIDL interfaces, system server services, and activities compile cleanly.
2. R2: Pure Binder IPC Window Bridge — replace reflection access (`Class.forName("com.android.server.linux.LinuxWindowBridgeService")`) in `LinuxAppProxyActivity.java` with canonical Binder IPC via `ILinuxWindowBridge.aidl`, connecting SurfaceView/Surface lifecycle.
3. R3: Single-Secret HMAC Key Agreement & Startup Initiator — establish single 32-byte secret agreement between Host Java, C++ daemon, and Guest agent (`android_bridge.token=<hex_secret>`), Host C++ listening on AF_VSOCK port 5000, Guest agent initiating boot handshake connection to Host (CID 2, Port 5000).
4. R4: Functional Permission Decision Component in `LinuxPermissionActivity` — process incoming `app_id` and permission op requests with AppOps integration.

Acceptance Criteria:
- No duplicate methods, unclosed braces, or compilation syntax errors in any Java files.
- App layer does not import or reflect upon `com.android.server.*` private implementation classes.
- All AIDL methods match their Java consumer callers in parameter types and counts.
- Host and Guest use identical 32-byte binary secrets to compute/verify RFC 2104 HMAC-SHA256 signatures.
- Guest initiates startup handshake connection to Host (CID 2, Port 5000), transitioning Host VM state to `RUNNING`.
- ARM64 build (`cargo check --target aarch64-unknown-linux-gnu`) passes cleanly with zero warnings or errors.
- All unit and empirical tests pass.

Decompose the work into milestones/subtasks, dispatch worker/reviewer subagents as appropriate, maintain `plan.md` and `progress.md` in your working directory, and report back when all acceptance criteria are met and you claim victory.
</USER_REQUEST>
