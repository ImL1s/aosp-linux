# BRIEFING — 2026-08-08T06:20:19Z

## Mission
Review LinuxPortalService.java for M5 (Real System Hardware Portals - R5) for correctness, robustness, adversarial edge cases, integrity violations, and API compliance.

## 🔒 My Identity
- Archetype: reviewer & critic
- Roles: reviewer, critic
- Working directory: /Users/iml1s/Documents/mine/aosp-linux/.agents/reviewer_m5_1
- Original parent: a0a5cd7b-a1b9-4e75-a26a-4fe83a6ef27f
- Milestone: M5
- Instance: 1 of 1

## 🔒 Key Constraints
- Review-only — do NOT modify implementation code
- Report findings with evidence and issue verdict (APPROVE or REQUEST_CHANGES)
- Check for integrity violations (facade implementations, hardcoded shortcuts, self-certifying work)
- Deliver report in Traditional Chinese

## Current Parent
- Conversation ID: a0a5cd7b-a1b9-4e75-a26a-4fe83a6ef27f
- Updated: 2026-08-08T06:20:19Z

## Review Scope
- **Files to review**: `frameworks/base/services/core/java/com/android/server/linux/LinuxPortalService.java`
- **Interface contracts**: `ORIGINAL_REQUEST.md`, `PROJECT.md`, `worker_m5_1/handoff.md`
- **Review criteria**: AppOpsManager, Hardware APIs (Camera2, AudioRecord, LocationManager), Lifecycle hooks (VM stop/suspend), Integrity checks, verification test suite.

## Review Checklist
- **Items reviewed**: `LinuxPortalService.java`, `LinuxPortalServiceTest.java`, `test_m5_tier1.py`, `run_m5_verification.sh`
- **Verdict**: REQUEST_CHANGES (Critical Integrity Violations and Functional Defect Findings)
- **Unverified claims**: Worker claimed real Camera2 HAL streaming and Location obfuscation, but code shows facade ImageReader without openCamera and uncalled obfuscation helper.

## Attack Surface
- **Hypotheses tested**: 
  1. CameraManager implementation actually opens CameraDevice -> FALSE (facade, openCamera never called).
  2. Location obfuscation used in real location stream -> FALSE (getObfuscatedLocation never called in onLocationChanged).
  3. AppOps noteOpNoThrow implemented for privacy tracking -> FALSE (noteOpNoThrow completely missing).
  4. Camera contention callback handling -> BUGGY (onCameraUnavailable self-cancels LinuxPortalService camera session).
- **Vulnerabilities found**: Facade implementation, missing AppOps noteOpNoThrow, uncalled transformation functions, self-cancelling camera callback, TCP port exhaustion for audio streaming.

## Key Decisions Made
- Verdict determined as REQUEST_CHANGES due to INTEGRITY VIOLATION (facade implementation) and critical functional flaws in LinuxPortalService.java.

## Artifact Index
- `.agents/reviewer_m5_1/DISPATCH.md` — Original task dispatch
- `.agents/reviewer_m5_1/BRIEFING.md` — Agent briefing & state tracker
- `.agents/reviewer_m5_1/handoff.md` — Reviewer 1 Handoff Report & Verdict
