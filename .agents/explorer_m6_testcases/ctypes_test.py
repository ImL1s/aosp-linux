import ctypes
import ctypes.util

libc = ctypes.CDLL(ctypes.util.find_library("c"))

# ssize_t listxattr(const char *path, char *namebuf, size_t size, int options);
libc.listxattr.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t, ctypes.c_int]
libc.listxattr.restype = ctypes.c_ssize_t

# int removexattr(const char *path, const char *name, int options);
libc.removexattr.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
libc.removexattr.restype = ctypes.c_int

path = b"/Users/iml1s/Documents/mine/aosp-linux/.agents/teamwork_preview_explorer_survey_3/handoff.md"
buf = ctypes.create_string_buffer(4096)
res = libc.listxattr(path, buf, 4096, 0)
print("listxattr result:", res)
if res > 0:
    attrs = buf.raw[:res].decode('utf-8', errors='ignore').split('\x00')
    print("Attrs found:", attrs)
    for a in attrs:
        if a:
            r2 = libc.removexattr(path, a.encode('utf-8'), 0)
            print(f"Remove {a}: result {r2}")
