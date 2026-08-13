import os
import glob
import re
import subprocess
import sys

print("=== EMPIRICAL CHALLENGER VERIFICATION HARNESS FOR M1 ===")

REPO_ROOT = "/Users/iml1s/Documents/mine/aosp-linux"
ANDROID_JAR = "/Users/iml1s/Library/Android/sdk/platforms/android-35/android.jar"

# 1. Find all Java files
all_java_files = []
for root, dirs, files in os.walk(REPO_ROOT):
    if ".agents" in root or "build" in root or ".git" in root:
        continue
    for f in files:
        if f.endswith(".java"):
            all_java_files.append(os.path.join(root, f))

print(f"Total Java files found in repo (excluding .agents/git): {len(all_java_files)}")
for f in all_java_files:
    print(f" - {os.path.relpath(f, REPO_ROOT)}")

# 2. Check for com.android.server imports or reflections in packages/apps
print("\n--- Checking for com.android.server imports/reflection in packages/apps ---")
app_java_files = [f for f in all_java_files if "/packages/apps/" in f]
violations = []
for filepath in app_java_files:
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as file:
        lines = file.readlines()
        for idx, line in enumerate(lines, 1):
            if "com.android.server" in line and not line.strip().startswith("//"):
                violations.append((os.path.relpath(filepath, REPO_ROOT), idx, line.strip()))

if violations:
    print(f"CRITICAL DEFECT: Found {len(violations)} illegal com.android.server references/reflections in packages/apps:")
    for v in violations:
        print(f"  {v[0]}:{v[1]}: {v[2]}")
else:
    print("PASS: No com.android.server references in packages/apps.")

# 3. Test compilation of packages/apps/LinuxTerminal
print("\n--- Testing javac compilation of packages/apps/LinuxTerminal ---")
cmd_terminal = [
    "javac",
    "-classpath", f"{ANDROID_JAR}:{REPO_ROOT}/frameworks/base/core/java",
    "-sourcepath", f"{REPO_ROOT}/packages/apps/LinuxTerminal/src:{REPO_ROOT}/frameworks/base/core/java",
    "-d", "/tmp/classes_terminal_test",
    "-Xlint:all"
] + [f for f in app_java_files if "LinuxTerminal" in f]

res = subprocess.run(cmd_terminal, capture_output=True, text=True)
print(f"Exit code: {res.returncode}")
if res.stdout:
    print("STDOUT:\n" + res.stdout)
if res.stderr:
    print("STDERR:\n" + res.stderr)

# 4. Test compilation of packages/apps/Launcher3
print("\n--- Testing javac compilation of packages/apps/Launcher3 ---")
launcher_files = [f for f in app_java_files if "Launcher3" in f]
if launcher_files:
    cmd_launcher = [
        "javac",
        "-classpath", f"{ANDROID_JAR}:{REPO_ROOT}/frameworks/base/core/java",
        "-sourcepath", f"{REPO_ROOT}/packages/apps/Launcher3/src:{REPO_ROOT}/frameworks/base/core/java",
        "-d", "/tmp/classes_launcher_test",
        "-Xlint:all"
    ] + launcher_files
    res_l = subprocess.run(cmd_launcher, capture_output=True, text=True)
    print(f"Exit code: {res_l.returncode}")
    if res_l.stdout:
        print("STDOUT:\n" + res_l.stdout)
    if res_l.stderr:
        print("STDERR:\n" + res_l.stderr)

# 5. Test compilation of SystemServer (frameworks/base/services/core/java)
print("\n--- Testing javac compilation of SystemServer linux services ---")
system_server_files = [f for f in all_java_files if "/frameworks/base/services/core/java/com/android/server/linux/" in f]
cmd_sys = [
    "javac",
    "-classpath", f"{ANDROID_JAR}:{REPO_ROOT}/frameworks/base/core/java:{REPO_ROOT}/frameworks/base/services/core/java",
    "-sourcepath", f"{REPO_ROOT}/frameworks/base/core/java:{REPO_ROOT}/frameworks/base/services/core/java",
    "-d", "/tmp/classes_sys_test",
    "-Xlint:all"
] + system_server_files

res_sys = subprocess.run(cmd_sys, capture_output=True, text=True)
print(f"Exit code: {res_sys.returncode}")
if res_sys.stdout:
    print("STDOUT:\n" + res_sys.stdout)
if res_sys.stderr:
    print("STDERR:\n" + res_sys.stderr)

print("\n=== Verification Harness Complete ===")
