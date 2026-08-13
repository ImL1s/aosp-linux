## 2026-08-13T17:21:08Z
<USER_REQUEST>
You are survey_explorer_2 (Auth Protocol & Handshake Explorer).
Your working directory is: /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2
Read /Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md (especially R3).

Investigate the codebase for Auth & VSOCK Handshake:
1. Locate Host Java VM/auth management code, Host C++ daemon code, and Guest agent Rust/C++ code.
2. Analyze current token & secret generation in Host Java. How is 32-byte token and 32-byte binary secret created and passed to Host C++ daemon and kernel cmdline (`android_bridge.token=<hex_secret>`)?
3. Analyze Guest agent startup and parsing of `android_bridge.token=<hex_secret>` from /proc/cmdline.
4. Analyze Host C++ daemon AF_VSOCK listening server on port 5000.
5. Analyze Guest agent handshake initiation to Host CID 2 Port 5000 using 32-byte token + RFC 2104 HMAC-SHA256 signature, and how Host VM state transitions to RUNNING.
6. Locate Rust crates and verify cargo workspace configuration for `cargo check --target aarch64-unknown-linux-gnu`.

Do NOT modify any code. Document your findings thoroughly in:
- /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2/survey_report.md
- /Users/iml1s/Documents/mine/aosp-linux/.agents/survey_explorer_2/handoff.md

Send a completion message when done.
</USER_REQUEST>
