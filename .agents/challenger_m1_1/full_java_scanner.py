import os
import re

REPO_ROOT = "/Users/iml1s/Documents/mine/aosp-linux"

print("=== FULL REPOSITORY JAVA AST & SYNTAX INTEGRITY SCAN ===")

all_java_files = []
for root, dirs, files in os.walk(REPO_ROOT):
    if ".agents" in root or "build" in root or ".git" in root or "target" in root:
        continue
    for f in files:
        if f.endswith(".java"):
            all_java_files.append(os.path.join(root, f))

syntax_errors = []
brace_errors = []
duplicate_methods = []

for filepath in all_java_files:
    rel_path = os.path.relpath(filepath, REPO_ROOT)
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()

    # 1. Check brace count
    open_braces = content.count('{')
    close_braces = content.count('}')
    if open_braces != close_braces:
        brace_errors.append((rel_path, f"Open braces: {open_braces}, Close braces: {close_braces}"))

    # 2. Check duplicate method signatures in same file
    # Simple regex for method declarations: [public/protected/private/static/final/synchronized] <return_type> <method_name>(<params>) {
    method_lines = []
    lines = content.splitlines()
    for idx, line in enumerate(lines, 1):
        line_s = line.strip()
        m = re.match(r'^(public|private|protected)\s+[\w\<\>\[\]\s]+\s+(\w+)\s*\(([^)]*)\)\s*\{?', line_s)
        if m and not line_s.startswith("//") and "class " not in line_s and "interface " not in line_s:
            method_name = m.group(2)
            params = m.group(3).strip()
            method_sig = f"{method_name}({params})"
            method_lines.append((method_sig, idx, line_s))

    sig_map = {}
    for sig, idx, line_s in method_lines:
        if sig in sig_map:
            duplicate_methods.append((rel_path, sig, sig_map[sig], idx))
        else:
            sig_map[sig] = idx

print(f"\nChecked {len(all_java_files)} Java files.")

if brace_errors:
    print(f"\nCRITICAL DEFECT: Brace mismatch errors ({len(brace_errors)} files):")
    for path, err in brace_errors:
        print(f"  - {path}: {err}")
else:
    print("\nPASS: All Java files have balanced curly braces ({ }).")

if duplicate_methods:
    print(f"\nCRITICAL DEFECT: Duplicate method signatures in same file ({len(duplicate_methods)} instances):")
    for path, sig, line1, line2 in duplicate_methods:
        print(f"  - {path}: method '{sig}' declared on line {line1} and line {line2}")
else:
    print("\nPASS: No duplicate method declarations found in any Java file.")

print("\n=== SCAN COMPLETED ===")
