## 2026-08-06T06:55:10Z
You are Challenger 2 Iteration 2 (teamwork_preview_challenger) for Milestone M2.

Working Directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m2_i2_2
Workspace Root: /Users/iml1s/Documents/mine/aosp-linux

MANDATORY DOCUMENTS TO READ FIRST:
1. /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md
2. /Users/iml1s/Documents/mine/aosp-linux/PROJECT.md
3. /Users/iml1s/Documents/mine/aosp-linux/.agents/worker_m2_i2/handoff.md

YOUR MISSION:
Empirically stress-test LUKS2 CE encryption, Vsock 3-port isolation, C++ compilation, and HMAC-SHA256 authentication (F-R2-003, F-R2-004, F-R2-005):
- Verify C++ native build compiles cleanly without `AuthHandshakePayload` redefinition errors.
- Stress-test LUKS2 CE encryption: persistent key derivation across unlocks, key memory zeroing on lock (`Arrays.fill`), corrupted LUKS header magic (`LUKS\xba\xbe`).
- Stress-test Vsock 3-port isolation: unauthorized CID rejection (CID != 3), port 5001/5002 access before port 5000 auth, framing magic (`0x56534F4B`), payload >16MB cap.
- Stress-test HMAC auth: invalid token mismatch (`SECURITY_ALERT`), 5s handshake timeout, replayed single-use token rejection, constant-time byte comparison.

Run C++ native test `./build_out/bin/linux_bridge_test` and `python3 tests/e2e/runner.py`.
Write stress test report and verdict (APPROVE / FAIL) to `/Users/iml1s/Documents/mine/aosp-linux/.agents/challenger_m2_i2_2/handoff.md` and send a message when done.
