import os

git_dir = "/Users/iml1s/Documents/mine/aosp-linux/.git"
print(".git exists:", os.path.exists(git_dir))
if os.path.exists(git_dir):
    for sub in ["config", "HEAD", "index", "objects"]:
        p = os.path.join(git_dir, sub)
        try:
            with open(p, "rb") as f:
                print(f"Readable .git/{sub}: {len(f.read())} bytes")
        except Exception as e:
            print(f"ERR .git/{sub}: {e}")
