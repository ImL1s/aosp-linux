import os

path = "/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_3/handoff.md"

try:
    attrs = os.listxattr(path)
    print("Xattrs:", attrs)
    for a in attrs:
        try:
            os.removexattr(path, a)
            print(f"Removed {a}")
        except Exception as e:
            print(f"Failed to remove {a}: {e}")
except Exception as e:
    print("listxattr error:", e)
