#!/bin/sh
# Guest Early Boot Script: OverlayFS & Decrypted Home Mount (F-R2-002)
set -e

echo "[Guest Mount] Mounting 4-Layer Storage Image Layout in Guest..."

# Prepare mount points
mkdir -p /mnt/lower /mnt/overlay /mnt/upper /mnt/work

# Mount Layer 1: base_rootfs.img (/dev/vda) as read-only lowerdir
if [ -b /dev/vda ]; then
    echo "[Guest Mount] Mounting /dev/vda on /mnt/lower (ro)..."
    mount -o ro /dev/vda /mnt/lower || true
fi

# Mount Layer 2: custom_overlay.img (/dev/vdb) as read-write overlay storage
if [ -b /dev/vdb ]; then
    echo "[Guest Mount] Mounting /dev/vdb on /mnt/overlay (rw)..."
    mount -o rw /dev/vdb /mnt/overlay || true
fi

# Mount OverlayFS for /etc, /var, /usr with Recovery & ENOSPC Logic
for dir in etc var usr; do
    mkdir -p "/mnt/overlay/upper/$dir" "/mnt/overlay/work/$dir"
    mkdir -p "/$dir"
    echo "[Guest Mount] Setting up OverlayFS for /$dir..."

    # Check free disk space on /mnt/overlay (ENOSPC space pre-check)
    FREE_KB=$(df -k /mnt/overlay 2>/dev/null | awk 'NR==2 {print $4}' || echo "100000")
    if [ "${FREE_KB:-0}" -lt 1024 ]; then
        echo "WARNING: ENOSPC / Low space on /mnt/overlay (${FREE_KB}KB available). Initiating upperdir wipe recovery / purging workdir cache for /$dir..." >&2
        rm -rf "/mnt/overlay/work/$dir"/* 2>/dev/null || true
    fi

    # Attempt OverlayFS mount
    if ! mount -t overlay overlay \
        -o "lowerdir=/mnt/lower/$dir,upperdir=/mnt/overlay/upper/$dir,workdir=/mnt/overlay/work/$dir" \
        "/$dir" 2>/dev/null; then
        echo "[Guest Mount] Warning: OverlayFS mount for /$dir failed. Initiating upperdir wipe recovery / purging workdir and upperdir..." >&2
        rm -rf "/mnt/overlay/upper/$dir"/* "/mnt/overlay/work/$dir"/*
        mkdir -p "/mnt/overlay/upper/$dir" "/mnt/overlay/work/$dir"

        # Retry OverlayFS mount after cleanup
        if ! mount -t overlay overlay \
            -o "lowerdir=/mnt/lower/$dir,upperdir=/mnt/overlay/upper/$dir,workdir=/mnt/overlay/work/$dir" \
            "/$dir" 2>/dev/null; then
            echo "[Guest Mount] Error: OverlayFS mount recovery for /$dir failed. Falling back to read-only bind mount from lowerdir..." >&2
            if [ -d "/mnt/lower/$dir" ]; then
                mount --bind -o ro "/mnt/lower/$dir" "/$dir" || echo "Warning: Fallback bind mount failed for /$dir"
            fi
        else
            echo "[Guest Mount] OverlayFS mount recovery for /$dir succeeded"
        fi
    fi
done

# Mount Layer 3: Decrypted user_home (/dev/vdc) on /home/user (rw)
if [ -b /dev/vdc ]; then
    echo "[Guest Mount] Mounting decrypted volume /dev/vdc on /home/user (rw)..."
    mkdir -p /home/user
    mount -o rw /dev/vdc /home/user || echo "Warning: Failed to mount /dev/vdc on /home/user"
fi

# Mount Layer 4: Virtiofs Bi-directional Sharing (/mnt/shared)
mkdir -p /mnt/shared
echo "[Guest Mount] Mounting virtiofs linux_shared on /mnt/shared..."
mount -t virtiofs linux_shared /mnt/shared -o rw,noatime,cache=always,dax 2>/dev/null || echo "Warning: Virtiofs mount /mnt/shared fallback or unavailable in simulation"

echo "[Guest Mount] OverlayFS & User Home mounting complete."
