## 2026-08-06T06:46:40Z
<USER_REQUEST>
You are Explorer 2 Iteration 2 (teamwork_preview_explorer) for Milestone M2.

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i2_2
Workspace Root: /Users/iml1s/Documents/mine/aosp-linux

MANDATORY DOCUMENTS TO READ FIRST:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/auditor_m2_1/handoff.md
4. /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m2_2/handoff.md

YOUR MISSION:
Formulate an authentic remediation strategy for C++ Native Daemon (`system/linux_bridge/`) and Java Service (`LinuxManagerService.java`):
1. Fix compiler error: resolve `redefinition of 'AuthHandshakePayload'` between `hmac_auth.h:31` and `vsock_framing.h:50`.
2. Remove dummy 32-byte XOR fallback in `system/linux_bridge/hmac_auth.cpp`. Implement genuine HMAC-SHA256 calculation.
3. Implement genuine `AF_VSOCK` socket binding and listening in `vsock_server.cpp` (Port 5000, 5001, 5002) with CID 3 check.
4. Fix `LinuxManagerService.java`: replace random `new SecureRandom()` key generation on user unlock with persistent Android Keymaster / CE storage key derivation.

Write your detailed remediation report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m2_i2_2/handoff.md` and send a message when complete.
</USER_REQUEST>
