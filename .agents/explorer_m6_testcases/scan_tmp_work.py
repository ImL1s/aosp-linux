import os

base = "/tmp/aosp-linux-work/aosp-linux"
print("Base exists:", os.path.exists(base))

for root, dirs, files in os.walk(base):
    rel = os.path.relpath(root, base)
    if "tests" in rel or rel == ".":
        print(f"[{rel}]")
        for f in files:
            p = os.path.join(root, f)
            sz = os.path.getsize(p)
            print(f"  {f} ({sz} bytes)")
