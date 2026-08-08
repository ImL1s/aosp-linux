import os, ast, re

base_dir = "/tmp/aosp-linux-work/aosp-linux/tests/e2e"

files = [
    "tier1_feature_coverage/test_m1_tier1.py",
    "tier1_feature_coverage/test_m2_tier1.py",
    "tier1_feature_coverage/test_m3_tier1.py",
    "tier1_feature_coverage/test_m4_tier1.py",
    "tier1_feature_coverage/test_m5_tier1.py",
    "tier2_boundary_corner/test_m1_tier2.py",
    "tier2_boundary_corner/test_m2_tier2.py",
    "tier2_boundary_corner/test_m3_tier2.py",
    "tier2_boundary_corner/test_m4_tier2.py",
    "tier2_boundary_corner/test_m5_tier2.py",
    "tier3_cross_feature/test_pairwise_matrix.py",
    "tier4_real_world/test_scenarios.py"
]

out = []

for rel_path in files:
    full_path = os.path.join(base_dir, rel_path)
    with open(full_path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    
    out.append(f"\n================================================================================")
    out.append(f"FILE: {rel_path} ({len(lines)} lines)")
    out.append(f"================================================================================")
    
    for idx, line in enumerate(lines, 1):
        l_str = line.strip()
        if l_str.startswith("class Test") or l_str.startswith("def test_"):
            out.append(f"\nL{idx:4d}: {l_str}")
        elif "assert" in l_str or "mock_env" in l_str or "checkpolicy" in l_str:
            out.append(f"  L{idx:4d}: {l_str}")

out_text = "\n".join(out)
with open("/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m6_testcases/all_tier_details.txt", "w") as f:
    f.write(out_text)

print(f"Details dumped: {len(out_text)} bytes")
