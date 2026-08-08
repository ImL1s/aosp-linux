import os, re, glob

base_dir = "/tmp/aosp-linux-work/aosp-linux/tests/e2e"

files_to_inspect = [
    # Tier 1
    "tier1_feature_coverage/test_m1_tier1.py",
    "tier1_feature_coverage/test_m2_tier1.py",
    "tier1_feature_coverage/test_m3_tier1.py",
    "tier1_feature_coverage/test_m4_tier1.py",
    "tier1_feature_coverage/test_m5_tier1.py",
    # Tier 2
    "tier2_boundary_corner/test_m1_tier2.py",
    "tier2_boundary_corner/test_m2_tier2.py",
    "tier2_boundary_corner/test_m3_tier2.py",
    "tier2_boundary_corner/test_m4_tier2.py",
    "tier2_boundary_corner/test_m5_tier2.py",
    # Tier 3
    "tier3_cross_feature/test_pairwise_matrix.py",
    # Tier 4
    "tier4_real_world/test_scenarios.py",
    # Framework & Runner
    "framework/assertions.py",
    "framework/mock_env.py",
    "runner.py",
]

out = []

for rel_path in files_to_inspect:
    full_path = os.path.join(base_dir, rel_path)
    if not os.path.exists(full_path):
        out.append(f"FILE NOT FOUND: {rel_path}\n")
        continue
    
    with open(full_path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    
    out.append(f"="*80)
    out.append(f"FILE: {rel_path} ({len(lines)} lines)")
    out.append(f"="*80)
    
    curr_func = "GLOBAL"
    assertions_in_func = []
    
    for idx, line in enumerate(lines, 1):
        line_str = line.strip()
        if line_str.startswith("def test_") or line_str.startswith("class "):
            curr_func = line_str.split("(")[0]
            out.append(f"\n--- {curr_func} (line {idx}) ---")
        
        if "assert" in line_str or "self.assert" in line_str or "check_" in line_str:
            out.append(f"  L{idx:4d}: {line_str}")

out_text = "\n".join(out)
with open("/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m6_testcases/all_assertions_summary.txt", "w") as f:
    f.write(out_text)

print(f"Summary written, total length {len(out_text)} bytes")
