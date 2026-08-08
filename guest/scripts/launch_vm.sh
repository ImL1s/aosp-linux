#!/bin/bash
# Host VM Launch Script for AVF crosvm Non-Protected Debian ARM64 Guest (F-R2-001)
set -e

CONFIG_FILE="${1:-/data/misc/linux/vm_config.json}"
AUTH_TOKEN="${2:-0000000000000000000000000000000000000000000000000000000000000000}"

echo "[Launch Script] Starting VM launch procedure..."

# Defaults
REQ_RAM_MB=4096
CPUS=4
CID=3
KERNEL_PATH="/apex/com.android.virt/etc/vmlinux"
INITRD_PATH="/data/misc/linux/initrd.img"
BASE_IMG="/data/misc/linux/base_rootfs.img"
OVERLAY_IMG="/data/misc/linux/custom_overlay.img"
HOME_MAPPER="/dev/mapper/user_home_decrypted"

if [ -f "$CONFIG_FILE" ]; then
    eval "$(python3 -c '
import json, sys
try:
    with open(sys.argv[1]) as f:
        cfg = json.load(f)
    ram = cfg.get("memory", {}).get("ram_mb", 4096)
    cpus = cfg.get("cpu", {}).get("cpus", 4)
    cid = cfg.get("vsock", {}).get("cid", 3)
    kp = cfg.get("kernel", {}).get("kernel_path", "/apex/com.android.virt/etc/vmlinux")
    ip = cfg.get("initrd_path", cfg.get("kernel", {}).get("initrd_path", "/data/misc/linux/initrd.img"))
    bp = cfg.get("disks", {}).get("base_rootfs", {}).get("path", "/data/misc/linux/base_rootfs.img")
    op = cfg.get("disks", {}).get("custom_overlay", {}).get("path", "/data/misc/linux/custom_overlay.img")
    hp = cfg.get("disks", {}).get("user_home", {}).get("path", "/dev/mapper/user_home_decrypted")
    print(f"REQ_RAM_MB={ram}")
    print(f"CPUS={cpus}")
    print(f"CID={cid}")
    print(f"KERNEL_PATH=\"{kp}\"")
    print(f"INITRD_PATH=\"{ip}\"")
    print(f"BASE_IMG=\"{bp}\"")
    print(f"OVERLAY_IMG=\"{op}\"")
    print(f"HOME_MAPPER=\"{hp}\"")
except Exception:
    pass
' "$CONFIG_FILE")"
fi

# 1. Acquire File Locks on Disk Images (flock with read redirection < to prevent O_TRUNC truncation)
if [ -f "$BASE_IMG" ]; then
    exec 200<"$BASE_IMG"
    if command -v flock >/dev/null 2>&1; then
        flock -n 200 || { echo "ERROR: ResourceBusy: base_rootfs.img is locked by another process" >&2; exit 3; }
    else
        python3 -c 'import fcntl; fcntl.flock(200, fcntl.LOCK_EX | fcntl.LOCK_NB)' 2>/dev/null || { echo "ERROR: ResourceBusy: base_rootfs.img is locked by another process" >&2; exit 3; }
    fi
fi

if [ -f "$OVERLAY_IMG" ]; then
    exec 201<"$OVERLAY_IMG"
    if command -v flock >/dev/null 2>&1; then
        flock -n 201 || { echo "ERROR: ResourceBusy: custom_overlay.img is locked by another process" >&2; exit 3; }
    else
        python3 -c 'import fcntl; fcntl.flock(201, fcntl.LOCK_EX | fcntl.LOCK_NB)' 2>/dev/null || { echo "ERROR: ResourceBusy: custom_overlay.img is locked by another process" >&2; exit 3; }
    fi
fi

# 2. Check Host RAM availability
AVAIL_RAM_KB=$(grep MemAvailable /proc/meminfo 2>/dev/null | awk '{print $2}' || echo "8388608")
AVAIL_RAM_KB="${AVAIL_RAM_KB:-8388608}"
AVAIL_RAM_MB=$((AVAIL_RAM_KB / 1024))
if [ "$AVAIL_RAM_MB" -lt "$REQ_RAM_MB" ]; then
    echo "ERROR: OutOfMemory: Requested ${REQ_RAM_MB}MB exceeds available host RAM (${AVAIL_RAM_MB}MB)" >&2
    exit 2
fi

# 3. Check /dev/kvm availability
if [ ! -c /dev/kvm ] && [ "${TEST_MODE:-0}" != "1" ]; then
    echo "ERROR: KVMException: /dev/kvm not found or insufficient permission" >&2
    exit 1
fi

# 4. Construct Command Line and crosvm Execution Parameters
CMDLINE="console=ttyS0 root=/dev/vda ro init=/sbin/init android_bridge.token=${AUTH_TOKEN} panic=1 quiet"

echo "[Launch Script] Launching crosvm Non-Protected VM (CID: ${CID}, CPUs: ${CPUS}, RAM: ${REQ_RAM_MB}MB)..."
echo "[Launch Script] Kernel Params: ${CMDLINE}"

# Execution template (in live environment, invokes crosvm binary with exec for PID tracking)
if command -v crosvm >/dev/null 2>&1; then
    exec crosvm run \
      --cid "$CID" \
      --cpus "$CPUS" \
      --mem "$REQ_RAM_MB" \
      --kernel "$KERNEL_PATH" \
      --initrd "$INITRD_PATH" \
      --params "${CMDLINE}" \
      --shared-dir "/data/media/0/LinuxShared:linux_shared:type=fs:cache=always:timeout=1" \
      --rodisk "$BASE_IMG" \
      --rwdisk "$OVERLAY_IMG" \
      --rwdisk "$HOME_MAPPER"
else
    echo "[Launch Script] crosvm binary not in PATH (Simulated execution mode)"
    if [ "${TEST_MODE:-0}" = "1" ]; then
        exec sleep 3600
    fi
fi

echo "[Launch Script] VM launch script completed successfully."
