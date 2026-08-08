import ctypes, ctypes.util

libc = ctypes.CDLL(ctypes.util.find_library("c"))
libc.listxattr.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t, ctypes.c_int]
libc.listxattr.restype = ctypes.c_ssize_t

def get_attrs(path):
    buf = ctypes.create_string_buffer(4096)
    res = libc.listxattr(path.encode('utf-8'), buf, 4096, 0)
    if res > 0:
        return buf.raw[:res].decode('utf-8', errors='ignore').split('\x00')
    return f"res={res}"

for p in [
    "/Users/iml1s/Documents/mine/aosp-linux",
    "/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md",
    "/Users/iml1s/Documents/mine/aosp-linux/tests",
    "/Users/iml1s/Documents/mine/aosp-linux/.agents",
    "/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m6_testcases"
]:
    print(f"{p} -> {get_attrs(p)}")
