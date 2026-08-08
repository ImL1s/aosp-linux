import os, stat

def scan(dir_path):
    print(f"--- Scanning {dir_path} ---")
    try:
        entries = os.listdir(dir_path)
        print("Listdir success:", len(entries), "items")
        for e in sorted(entries):
            p = os.path.join(dir_path, e)
            st = os.stat(p)
            mode = stat.filemode(st.st_mode)
            readable = False
            if os.path.isfile(p):
                try:
                    with open(p, 'rb') as f:
                        f.read(10)
                        readable = True
                except Exception as ex:
                    readable = f"ERR: {ex}"
            else:
                readable = "DIR"
            print(f"{mode} {st.st_uid}:{st.st_gid} {st.st_size:>8} {e} -> {readable}")
    except Exception as ex:
        print("Listdir failed:", ex)

scan("/Users/iml1s/Documents/mine/aosp-linux")
scan("/Users/iml1s/Documents/mine/aosp-linux/.agents")
scan("/Users/iml1s/Documents/mine/aosp-linux/tests")
