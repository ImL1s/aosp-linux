import ctypes, ctypes.util
libc = ctypes.CDLL(ctypes.util.find_library("c"))
libc.listxattr.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t, ctypes.c_int]
libc.listxattr.restype = ctypes.c_ssize_t

buf = ctypes.create_string_buffer(4096)
res = libc.listxattr(b"/Users/iml1s/Documents/mine/aosp-linux/.agents/sub_orch_m6/SCOPE.md", buf, 4096, 0)
print("SCOPE listxattr res:", res)
