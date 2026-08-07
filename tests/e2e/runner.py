#!/usr/bin/env python3
"""
AOSP Dual-OS E2E Test Suite Runner CLI (`tests/e2e/runner.py`).

Usage:
  python3 runner.py [--tier {1,2,3,4}] [--feature FEATURE_ID] [--report REPORT_PATH] [--verbose] [--list]
"""

import sys
import os
import time
import argparse
import inspect
import importlib

# Ensure current directory is in sys.path
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

from framework import BaseTestCase, TestResult, TestStatus, MockEnvironment, ReportFormatter

# Mapping of tier numbers to candidate directory names
TIER_DIRS = {
    1: ["tier1_feature_coverage", "tier1"],
    2: ["tier2_boundary_corner", "tier2"],
    3: ["tier3_cross_feature", "tier3"],
    4: ["tier4_real_world", "tier4"],
}

DEFAULT_REPORT_PATH = "/Users/iml1s/Documents/mine/aosp-linux/tests/e2e_report.json"

def discover_test_classes(tier_filter: int = None) -> list:
    """
    Dynamically discover all BaseTestCase subclasses from tier directories
    (tier1, tier2, tier3, tier4 or their feature coverage aliases).
    """
    discovered_classes = []
    seen_class_keys = set()
    scanned_realpaths = set()

    target_tiers = [tier_filter] if tier_filter in TIER_DIRS else sorted(TIER_DIRS.keys())

    for tier_num in target_tiers:
        dir_candidates = TIER_DIRS[tier_num]
        for dir_name in dir_candidates:
            dir_path = os.path.join(BASE_DIR, dir_name)
            if not os.path.exists(dir_path):
                continue

            real_path = os.path.realpath(dir_path)
            if real_path in scanned_realpaths:
                continue
            scanned_realpaths.add(real_path)

            for filename in sorted(os.listdir(dir_path)):
                if filename.startswith("test_") and filename.endswith(".py"):
                    module_name = f"{dir_name}.{filename[:-3]}"
                    try:
                        mod = importlib.import_module(module_name)
                        for attr_name in dir(mod):
                            attr = getattr(mod, attr_name)
                            if (
                                inspect.isclass(attr)
                                and issubclass(attr, BaseTestCase)
                                and attr is not BaseTestCase
                            ):
                                class_key = (attr.__module__, attr.__name__)
                                if class_key not in seen_class_keys:
                                    seen_class_keys.add(class_key)
                                    discovered_classes.append(attr)
                    except Exception as e:
                        print(f"Error importing module {module_name}: {e}", file=sys.stderr)

    # Sort tests deterministically by tier and test_id
    discovered_classes.sort(key=lambda cls: (getattr(cls, "tier", 1), getattr(cls, "test_id", "")))
    return discovered_classes

def list_tests(tests: list):
    """Print discovered test inventory."""
    print("\n" + "=" * 80)
    print("                    DISCOVERED E2E TEST INVENTORY                               ")
    print("=" * 80)
    print(f"{'TIER':<6} | {'FEATURE ID':<12} | {'TEST ID':<14} | {'TITLE'}")
    print("-" * 80)
    for test_cls in tests:
        print(f"Tier {getattr(test_cls, 'tier', 1):<1} | {getattr(test_cls, 'feature_id', ''):<12} | {getattr(test_cls, 'test_id', ''):<14} | {getattr(test_cls, 'title', '')}")
    print("-" * 80)
    print(f"Total Discovered Tests: {len(tests)}\n")

def main():
    parser = argparse.ArgumentParser(description="AOSP Dual-OS E2E Test Suite Runner")
    parser.add_argument("--tier", type=int, choices=[1, 2, 3, 4], help="Run tests for specific tier (1, 2, 3, 4)")
    parser.add_argument("--feature", type=str, help="Filter tests by Feature ID (e.g. F-R1-001)")
    parser.add_argument("--filter", type=str, help="Filter tests by substring in test_id, feature_id, or title")
    parser.add_argument(
        "--report",
        type=str,
        default=DEFAULT_REPORT_PATH,
        help=f"Path to write JSON test report (default: {DEFAULT_REPORT_PATH})"
    )
    parser.add_argument("--output-json", type=str, help="Alias for --report path")
    parser.add_argument("--verbose", action="store_true", help="Print verbose execution stack traces")
    parser.add_argument("--list", action="store_true", help="List all discovered tests and exit")

    args = parser.parse_args()

    report_path = args.output_json if args.output_json else args.report

    test_classes = discover_test_classes(tier_filter=args.tier)

    if args.feature:
        feat_pattern = args.feature.lower()
        test_classes = [
            cls for cls in test_classes
            if feat_pattern in getattr(cls, "feature_id", "").lower()
        ]

    if args.filter:
        pattern = args.filter.lower()
        test_classes = [
            cls for cls in test_classes
            if pattern in getattr(cls, "test_id", "").lower()
            or pattern in getattr(cls, "feature_id", "").lower()
            or pattern in getattr(cls, "title", "").lower()
        ]

    if args.list:
        list_tests(test_classes)
        sys.exit(0)

    if not test_classes:
        print("No matching test cases found.")
        sys.exit(0)

    print(f"\nExecuting {len(test_classes)} test case(s)...")
    start_time = time.time()
    results = []

    # Shared mock environment for test execution
    mock_env = MockEnvironment()

    for test_cls in test_classes:
        test_instance = test_cls(mock_env=mock_env)
        result = test_instance.execute()
        results.append(result)

        status_symbol = {
            TestStatus.PASS: "[PASS]",
            TestStatus.FAIL: "[FAIL]",
            TestStatus.ERROR: "[ERR ]",
            TestStatus.SKIP: "[SKIP]",
        }.get(result.status, "[????]")

        print(f"{status_symbol} Tier {result.tier} | {result.feature_id:<10} | {result.test_id:<12} | {result.name}")

        if args.verbose and result.status in (TestStatus.FAIL, TestStatus.ERROR):
            print(f"       └── Failure Details for {result.test_id}: {result.error_message}")
            if result.stack_trace:
                print(f"{result.stack_trace}")

    elapsed = time.time() - start_time

    # Output console summary
    ReportFormatter.print_console_summary(results, elapsed)

    # Generate JSON report
    if report_path:
        ReportFormatter.generate_json_report(results, elapsed, report_path)
        print(f"JSON test report saved to: {report_path}")

    # Return exit code 0 if all tests pass, 1 if any fails or errors
    has_failures = any(r.status in (TestStatus.FAIL, TestStatus.ERROR) for r in results)
    sys.exit(1 if has_failures else 0)

if __name__ == "__main__":
    main()
