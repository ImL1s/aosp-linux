path = "/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m6/SCOPE.md"
try:
    with open(path, "r") as f:
        print("SCOPE content len:", len(f.read()))
except Exception as e:
    print("SCOPE read error:", e)
