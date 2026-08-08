import os, re

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

for fname in files:
    path = os.path.join(base_dir, fname)
    with open(path, "r") as f:
        content = f.read()
    
    print(f"\n==========================================")
    print(f"ANALYZING FILE: {fname}")
    print(f"==========================================")
    
    # Find all test functions
    test_funcs = re.findall(r'def (test_[a-zA-Z0-9_]+)\([^)]*\):', content)
    print(f"Test functions ({len(test_funcs)}): {test_funcs[:10]}")
    
    # Find assertions
    assertions = [line.strip() for line in content.splitlines() if 'assert' in line]
    print(f"Total assertions: {len(assertions)}")
    
    # Sample assertions
    for a in assertions[:15]:
        print(f"  ASSERT: {a}")
