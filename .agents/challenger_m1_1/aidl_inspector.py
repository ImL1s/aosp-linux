import os
import re

REPO_ROOT = "/Users/iml1s/Documents/mine/aosp-linux"
AIDL_DIR = os.path.join(REPO_ROOT, "frameworks/base/core/java/android/system/linux")

print("=== CHECKING ALL AIDL INTERFACES vs JAVA STUBS & IMPLEMENTATIONS ===")

aidl_files = [f for f in os.listdir(AIDL_DIR) if f.endswith(".aidl") and f.startswith("I")]

for aidl_file in aidl_files:
    interface_name = aidl_file[:-5] # e.g. ILinuxManager
    aidl_path = os.path.join(AIDL_DIR, aidl_file)
    with open(aidl_path, "r", encoding="utf-8") as f:
        content = f.read()

    # Extract method signatures from AIDL
    # AIDL method pattern: return_type method_name(args);
    # Strip comments first
    clean_content = re.sub(r'//.*?\n|/\*.*?\*/', '', content, flags=re.DOTALL)
    methods = re.findall(r'([\w\<\>\[\]]+)\s+(\w+)\s*\(([^)]*)\);', clean_content)
    print(f"\nInterface: {interface_name} ({len(methods)} methods in AIDL):")
    for ret, name, args in methods:
        args_clean = ' '.join(args.split())
        print(f"  - {ret} {name}({args_clean})")

    # Find implementation across all java files in repo
    server_impls = []
    for root, dirs, files in os.walk(REPO_ROOT):
        if ".agents" in root or "build" in root or ".git" in root:
            continue
        for file in files:
            if file.endswith(".java"):
                file_path = os.path.join(root, file)
                with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                    java_code = f.read()
                    if f"extends {interface_name}.Stub" in java_code or f"implements {interface_name}" in java_code or f"new {interface_name}.Stub()" in java_code:
                        server_impls.append(file_path)

    if server_impls:
        for impl in server_impls:
            print(f"  Implemented in: {os.path.relpath(impl, REPO_ROOT)}")
            with open(impl, "r", encoding="utf-8", errors="ignore") as f:
                java_code = f.read()
            
            missing_methods = []
            for ret, name, args in methods:
                if not re.search(r'\b' + name + r'\s*\(', java_code):
                    missing_methods.append(name)
            if missing_methods:
                print(f"    CRITICAL DEFECT: Missing implementation for methods: {missing_methods}")
            else:
                print(f"    PASS: All {len(methods)} AIDL methods implemented in {os.path.basename(impl)}.")
    else:
        print(f"  WARNING / DEFECT: No Java implementation found for {interface_name}.Stub!")

