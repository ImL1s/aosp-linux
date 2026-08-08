for p in [
    "/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md",
    "/Users/iml1s/Documents/mine/aosp-linux/PROJECT.md",
    "/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m6/SCOPE.md",
    "/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_3/handoff.md"
]:
    try:
        with open(p, "r") as f:
            content = f.read()
            print(f"SUCCESS {p}: {len(content)} bytes")
    except Exception as e:
        print(f"FAIL {p}: {e}")
