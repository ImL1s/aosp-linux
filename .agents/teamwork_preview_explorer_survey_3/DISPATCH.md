# DISPATCH — Explorer 3 (Survey R5 & R6)

## Mission
Survey the AOSP Dual-OS codebase for Defect R5 (Real System Hardware Portals) and Defect R6 (Clean & Honest E2E Test Suite).

## Scope
1. **R5**: Locate `LinuxPortalService`, SAF provider, virtiofs & LUKS2 mount lifecycle code. Document in-memory portal models, required system service integrations (CameraManager/Camera2, AudioRecord, LocationManager, AppOpsManager), and SAF provider binding.
2. **R6**: Locate E2E test suite, test runner scripts, CI configs, test files containing fake passes, hardcoded mock responses, and static JSON readouts. Document all tests needing conversion to real IPC / socket / system checks.

## Inputs
- `/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md`

## Outputs
Write detailed survey report and handoff to `/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_3/handoff.md`.

## 2026-08-08T05:57:04Z
User Request: Scope: Investigate R5 (Real System Hardware Portals) & R6 (Clean & Honest E2E Test Suite).

