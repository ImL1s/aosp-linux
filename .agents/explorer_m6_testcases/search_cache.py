import os

def find_files(search_dir):
    matches = []
    try:
        for root, dirs, files in os.walk(search_dir):
            for f in files:
                if "ORIGINAL_REQUEST" in f or "PROJECT" in f or "test_" in f or "handoff" in f:
                    matches.append(os.path.join(root, f))
    except Exception as e:
        print("Walk error:", e)
    return matches

print("Searching ~/.gemini ...")
res = find_files("/Users/iml1s/.gemini")
for r in res[:20]:
    print("Found in gemini:", r)

print("Searching /tmp ...")
res2 = find_files("/tmp")
for r in res2[:20]:
    print("Found in /tmp:", r)
