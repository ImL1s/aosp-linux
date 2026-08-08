import os, ast

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

for rel_path in files:
    full_path = os.path.join(base_dir, rel_path)
    with open(full_path, "r", encoding="utf-8") as f:
        content = f.read()
    
    tree = ast.parse(content, filename=rel_path)
    print(f"\n================================================================================")
    print(f"FILE: {rel_path}")
    print(f"================================================================================")
    
    for node in tree.body:
        if isinstance(node, ast.ClassDef):
            print(f"Class: {node.name}")
            methods = [item.name for item in node.body if isinstance(item, ast.FunctionDef) and item.name.startswith("test_")]
            for m in methods:
                print(f"  - {m}")
