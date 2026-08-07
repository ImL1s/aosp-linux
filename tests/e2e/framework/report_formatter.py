"""
Report formatter for console output and JSON export.
"""

import json
import time
from typing import List, Dict, Any
from .base_test import TestResult, TestStatus

class ReportFormatter:
    @staticmethod
    def print_console_summary(results: List[TestResult], elapsed_sec: float):
        total = len(results)
        passed = sum(1 for r in results if r.status == TestStatus.PASS)
        failed = sum(1 for r in results if r.status == TestStatus.FAIL)
        errored = sum(1 for r in results if r.status == TestStatus.ERROR)
        skipped = sum(1 for r in results if r.status == TestStatus.SKIP)
        pass_rate = (passed / total * 100.0) if total > 0 else 0.0

        print("\n" + "=" * 80)
        print("                AOSP DUAL-OS E2E TEST EXECUTION REPORT                 ")
        print("=" * 80)

        for res in results:
            status_symbol = {
                TestStatus.PASS: "[PASS]",
                TestStatus.FAIL: "[FAIL]",
                TestStatus.ERROR: "[ERR ]",
                TestStatus.SKIP: "[SKIP]",
            }.get(res.status, "[????]")

            print(f"{status_symbol} Tier {res.tier} | {res.feature_id:<10} | {res.test_id:<12} | {res.name}")
            if res.status in (TestStatus.FAIL, TestStatus.ERROR) and res.error_message:
                print(f"       └── Failure Reason: {res.error_message}")

        print("-" * 80)
        print(f"TOTAL TESTS  : {total}")
        print(f"PASSED       : {passed}")
        print(f"FAILED       : {failed}")
        print(f"ERRORS       : {errored}")
        print(f"SKIPPED      : {skipped}")
        print(f"PASS RATE    : {pass_rate:.1f}%")
        print(f"DURATION     : {elapsed_sec:.2f} seconds")
        print("=" * 80 + "\n")

    @staticmethod
    def generate_json_report(results: List[TestResult], elapsed_sec: float, file_path: str):
        total = len(results)
        passed = sum(1 for r in results if r.status == TestStatus.PASS)
        failed = sum(1 for r in results if r.status == TestStatus.FAIL)
        errored = sum(1 for r in results if r.status == TestStatus.ERROR)
        skipped = sum(1 for r in results if r.status == TestStatus.SKIP)

        report_data = {
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "summary": {
                "total": total,
                "passed": passed,
                "failed": failed,
                "errored": errored,
                "skipped": skipped,
                "pass_rate_percent": round((passed / total * 100.0) if total > 0 else 0.0, 2),
                "duration_seconds": round(elapsed_sec, 4),
            },
            "results": [r.to_dict() for r in results],
        }

        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(report_data, f, indent=2)
