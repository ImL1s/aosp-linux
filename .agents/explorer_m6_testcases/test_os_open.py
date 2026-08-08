import os

p = "/Users/iml1s/Documents/mine/aosp-linux/ORIGINAL_REQUEST.md"
try:
    fd = os.open(p, os.O_RDONLY)
    data = os.read(fd, 200)
    os.close(fd)
    print("os.open SUCCESS:", data[:50])
except Exception as e:
    print("os.open FAIL:", e)
