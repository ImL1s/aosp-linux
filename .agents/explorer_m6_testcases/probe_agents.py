import os

agents = [
    "orchestrator", "sub_orch_m1", "sub_orch_m2", "sub_orch_m3", "sub_orch_m4", "sub_orch_m5", "sub_orch_m6",
    "explorer_1", "explorer_2", "explorer_3", "explorer_m6_testcases",
    "teamwork_preview_explorer_survey_1", "teamwork_preview_explorer_survey_2", "teamwork_preview_explorer_survey_3"
]

filenames = ["BRIEFING.md", "DISPATCH.md", "progress.md", "handoff.md", "SCOPE.md", "plan.md", "context.md", "analysis.md"]

base = "/Users/iml1s/Documents/mine/aosp-linux/.agents"

for a in agents:
    for f in filenames:
        p = os.path.join(base, a, f)
        try:
            with open(p, "r") as fp:
                data = fp.read()
                print(f"READABLE ({len(data)}b): .agents/{a}/{f}")
        except FileNotFoundError:
            pass
        except Exception as e:
            print(f"PERM_ERR ({e}): .agents/{a}/{f}")
