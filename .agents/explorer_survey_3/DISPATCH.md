## 2026-08-06T21:25:31Z
Investigate Requirement R3: "Deploy generated AOSP artifacts (LinuxManagerService, linux_manager.te, LinuxTerminal.apk, android-bridge-agent, guest images) to build_out/deployment/ directory and perform simulated target verification."

Please investigate:
1. Is build_out/deployment/ directory existing or created by deployment scripts?
2. Where are the deployment scripts, tools, or manifests located?
3. What are the exact target paths and filenames required in build_out/deployment/?
4. How is simulated target verification performed? Are there verification scripts or test runners for deployed artifacts?
