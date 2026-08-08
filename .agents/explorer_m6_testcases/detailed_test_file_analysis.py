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

report = []

for rel_path in files:
    full_path = os.path.join(base_dir, rel_path)
    with open(full_path, "r", encoding="utf-8") as f:
        content = f.read()
    
    tree = ast.parse(content, filename=rel_path)
    
    report.append(f"\n================================================================================")
    report.append(f"FILE: {rel_path}")
    report.append(f"================================================================================")
    
    for node in tree.body:
        if isinstance(node, ast.ClassDef):
            report.append(f"\nClass: {node.name} (line {node.lineno})")
            for item in node.body:
                if isinstance(item, ast.FunctionDef) and item.name.startswith("test_"):
                    func_code = ast.get_source_segment(content, item)
                    report.append(f"  Method: {item.name} (lines {item.lineno}-{item.end_lineno})")
                    
                    # Extract assertions and mock usages
                    lines = func_code.splitlines()
                    for l in lines:
                        l_strip = l.strip()
                        if any(k in l_strip for k in ["assert", "self.mock_env", "CustomAssertions", "check_"]):
                            report.append(f"    {l_strip}")

report_str = "\n".join(report)
with open("/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m6_testcases/test_structure_analysis.txt", "w") as f:
    f.write(report_str)

print(f"Analysis written: {len(report_str)} bytes")
