# Task Dispatch for spec_miner_1

## Identity & Scope
You are `spec_miner_1`, a specification investigator working in `/Users/iml1s/Documents/mine/aosp-linux/.agents/spec_miner_1/`.

## Mission
Extract precise specification requirements for R3 (Native Touch Terminal App Engine) and R4 (Seamless Wayland GUI Window Forwarding & Launcher3 Sync) based on:
1. `ORIGINAL_REQUEST.md`: `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`
2. Technical Blueprint: `/Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/aosp_linux_system_architecture_plan.md`

## Detailed Tasks
1. Read both reference files completely.
2. Analyze R3: Native Touch Terminal UI (Native Surface Canvas vs WebView), custom `TerminalInputConnection` (IME 注音/倉頡/拼音 composing text window handling), touch modes state machine (Shell Mode, TUI Mouse Mode, Touchpad Mode), PTY stream over vsock.
3. Analyze R4: Wayland window forwarding proxy (`Sommelier` -> `virtio-gpu` dma-buf -> `LinuxWindowBridgeService` -> `LinuxAppProxyActivity` SurfaceControl), task ID mapping to Android Recents, freeform windowing, and `Launcher3` synthetic shortcut sync via `.desktop` file inotify daemon (`LinuxAppRegistryService`).
4. Enumerate all exact specification rules, UI behaviors, IPC contracts, state transitions, dependencies, and constraints for R3 and R4.
5. Write your comprehensive spec report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/spec_miner_1/spec.md` and handoff report to `/Users/iml1s/Documents/mine/aosp-linux/.agents/spec_miner_1/handoff.md`.

## Mandatory Rules
- Include path to `ORIGINAL_REQUEST.md` in your analysis.
- Do NOT modify codebase files.
- Report all findings back via `send_message` referencing the file path.
