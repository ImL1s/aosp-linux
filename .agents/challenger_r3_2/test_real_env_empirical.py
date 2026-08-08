import os
import sys

# Ensure project root is in sys.path
project_root = "/Users/iml1s/Documents/mine/aosp-linux"
if project_root not in sys.path:
    sys.path.insert(0, project_root)

from tests.e2e.framework.real_env import SystemEnvironment

def test_hardware_methods_raise_environment_error():
    env = SystemEnvironment()
    methods_and_attributes = [
        ("verify_cts_verifier_compatibility", env.system_server.verify_cts_verifier_compatibility),
        ("measure_cts_idle_power_drop", env.system_server.measure_cts_idle_power_drop),
        ("verify_gsi_boot_compatibility", env.system_server.verify_gsi_boot_compatibility),
        ("measure_erofs_read_throughput", env.measure_erofs_read_throughput),
        ("measure_virtiofs_read_speed", env.measure_virtiofs_read_speed),
    ]

    print("=== Testing Un-overridden Calls (Expecting EnvironmentError) ===")
    for name, method in methods_and_attributes:
        try:
            val = method()
            print(f"[FAIL] {name}() did not raise EnvironmentError! Returned: {val}")
            sys.exit(1)
        except EnvironmentError as e:
            print(f"[PASS] {name}() correctly raised EnvironmentError: {e}")
        except Exception as e:
            print(f"[FAIL] {name}() raised unexpected exception type {type(e)}: {e}")
            sys.exit(1)

def test_hardware_methods_with_overrides():
    env = SystemEnvironment()
    print("\n=== Testing Explicit Overrides ===")
    
    # 1. verify_cts_verifier_compatibility
    env.system_server.cts_verifier_status = "PASS"
    res1 = env.system_server.verify_cts_verifier_compatibility()
    assert res1 == "PASS", f"Expected PASS, got {res1}"
    print(f"[PASS] verify_cts_verifier_compatibility override works: {res1}")

    # 2. measure_cts_idle_power_drop
    env.system_server.idle_power_drop_override = 0.35
    res2 = env.system_server.measure_cts_idle_power_drop()
    assert res2 == 0.35, f"Expected 0.35, got {res2}"
    print(f"[PASS] measure_cts_idle_power_drop override works: {res2}")

    # 3. verify_gsi_boot_compatibility
    env.system_server.gsi_boot_compatible = True
    res3 = env.system_server.verify_gsi_boot_compatibility()
    assert res3 is True, f"Expected True, got {res3}"
    print(f"[PASS] verify_gsi_boot_compatibility override works: {res3}")

    # 4. measure_erofs_read_throughput
    env.erofs_throughput_override = 512.5
    res4 = env.measure_erofs_read_throughput()
    assert res4 == 512.5, f"Expected 512.5, got {res4}"
    print(f"[PASS] measure_erofs_read_throughput override works: {res4}")

    # 5. measure_virtiofs_read_speed
    env.virtiofs_read_speed_override = 1024.0
    res5 = env.measure_virtiofs_read_speed()
    assert res5 == 1024.0, f"Expected 1024.0, got {res5}"
    print(f"[PASS] measure_virtiofs_read_speed override works: {res5}")

if __name__ == "__main__":
    test_hardware_methods_raise_environment_error()
    test_hardware_methods_with_overrides()
    print("\nALL REAL_ENV OVERRIDE & FALLBACK TESTS PASSED CLEANLY!")
