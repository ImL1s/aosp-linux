import os, glob

test_dir = "/tmp/aosp-linux-work/aosp-linux/tests/e2e"

test_files = glob.glob(os.path.join(test_dir, "**/*.py"), recursive=True)

print(f"Found {len(test_files)} test python files:")
for f in sorted(test_files):
    rel = os.path.relpath(f, test_dir)
    sz = os.path.getsize(f)
    print(f"  {rel} ({sz} bytes)")
