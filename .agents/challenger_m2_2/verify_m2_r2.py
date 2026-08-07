#!/usr/bin/env python3
import os
import struct
import sys
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

def inspect_guest_root_key(key_path):
    print(f"=== Inspecting {key_path} ===")
    if not os.path.exists(key_path):
        print(f"FAIL: {key_path} does not exist")
        return None
    with open(key_path, "rb") as f:
        key_bytes = f.read()
    try:
        pubkey = serialization.load_pem_public_key(key_bytes)
        if isinstance(pubkey, rsa.RSAPublicKey):
            key_size = pubkey.key_size
            print(f"Key type: RSA")
            print(f"Key size: {key_size} bits")
            return pubkey
        else:
            print(f"Key type: {type(pubkey)}")
            return None
    except Exception as e:
        print(f"Error parsing public key: {e}")
        return None

def inspect_vbmeta(vbmeta_path):
    print(f"\n=== Inspecting {vbmeta_path} ===")
    if not os.path.exists(vbmeta_path):
        print(f"FAIL: {vbmeta_path} does not exist")
        return
    file_size = os.path.getsize(vbmeta_path)
    print(f"File size: {file_size} bytes")
    with open(vbmeta_path, "rb") as f:
        data = f.read()
    magic = data[:4]
    print(f"Magic header: {magic} (Expected: b'AVB0')")

    if len(data) >= 44:
        hdr = struct.unpack('<IIQQIQI', data[4:44])
        major, minor, auth_sz, aux_sz, algo, rollback, flags = hdr
        print(f"Header struct breakdown:")
        print(f"  required_libavb_version_major: {major}")
        print(f"  required_libavb_version_minor: {minor}")
        print(f"  authentication_data_block_size: {auth_sz}")
        print(f"  auxiliary_data_block_size: {aux_sz}")
        print(f"  algorithm_type: {algo} (1=SHA256_RSA2048, 3=SHA256_RSA4096)")
        print(f"  rollback_index: {rollback}")
        print(f"  flags: {flags}")

        if auth_sz == 0:
            print("  [ALERT] authentication_data_block_size is 0 (No RSA signature in vbmeta!)")
        if aux_sz == 0:
            print("  [ALERT] auxiliary_data_block_size is 0 (No public key / descriptors in vbmeta!)")

def inspect_luks2(home_path):
    print(f"\n=== Inspecting LUKS2 Header in {home_path} ===")
    if not os.path.exists(home_path):
        print(f"FAIL: {home_path} does not exist")
        return
    file_size = os.path.getsize(home_path)
    print(f"File size: {file_size:,} bytes ({file_size / (1024**2):.2f} MB)")
    with open(home_path, "rb") as f:
        header_512 = f.read(512)

    magic_16 = header_512[:16]
    print(f"First 16 bytes: {magic_16}")
    is_luks = magic_16.startswith(b'LUKS\xba\xbe') or magic_16.startswith(b'LUKS2')
    print(f"Valid LUKS/LUKS2 header magic present: {is_luks}")
    if not is_luks:
        print("  [ALERT] user_home.img is missing LUKS2 header magic (all zero bytes!)")

def inspect_all_images(img_dir):
    print(f"\n=== Inspecting Storage Image Sizes in {img_dir} ===")
    expected_sizes = {
        "base_rootfs.img": 2500 * 1024 * 1024,
        "custom_overlay.img": 4000 * 1024 * 1024,
        "user_home.img": 5000 * 1024 * 1024,
    }
    if not os.path.exists(img_dir):
        print(f"FAIL: {img_dir} does not exist")
        return
    for fname, exp_sz in expected_sizes.items():
        p = os.path.join(img_dir, fname)
        if os.path.exists(p):
            actual_sz = os.path.getsize(p)
            match = "MATCH" if actual_sz == exp_sz else f"MISMATCH (expected {exp_sz})"
            print(f"  {fname}: {actual_sz:,} bytes ({actual_sz / (1024**2):.0f} MB) -> {match}")
        else:
            print(f"  {fname}: MISSING")

if __name__ == "__main__":
    inspect_guest_root_key("system/etc/security/avb/guest_root_key.pub")
    inspect_vbmeta("build_out/guest_images/vbmeta.img")
    inspect_luks2("build_out/guest_images/user_home.img")
    inspect_all_images("build_out/guest_images")
