import ctypes, ctypes.util

libc = ctypes.CDLL(ctypes.util.find_library("c"))

# ssize_t getxattr(const char *path, const char *name, void *value, size_t size, uint32_t position, int options);
libc.getxattr.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_int]
libc.getxattr.restype = ctypes.c_ssize_t

# int setxattr(const char *path, const char *name, const void *value, size_t size, uint32_t position, int options);
libc.setxattr.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_uint32, ctypes.c_int]
libc.setxattr.restype = ctypes.c_int

src = b"/Users/iml1s/Documents/mine/aosp-linux/.agents/explorer_m6_testcases"
dst = b"/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md"

buf = ctypes.create_string_buffer(4096)
res = libc.getxattr(src, b"com.apple.macl", buf, 4096, 0, 0)
print("getxattr com.apple.macl res:", res)

if res > 0:
    sres = libc.setxattr(dst, b"com.apple.macl", buf, res, 0, 0)
    print("setxattr com.apple.macl res:", sres)
    
    # Now try opening dst
    try:
        with open(dst, "r") as f:
            print("dst content prefix:", f.read(100))
    except Exception as e:
        print("Open dst failed:", e)
