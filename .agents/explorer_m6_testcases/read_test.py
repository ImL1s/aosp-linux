import os

path = "/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_3/handoff.md"
print("Exists:", os.path.exists(path))
try:
    with open(path, "r") as f:
        print(f.read()[:200])
except Exception as e:
    print("Error:", e)
