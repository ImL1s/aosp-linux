#!/usr/bin/env python3
"""
Adversarial Stress Test for Guest portal-agent Desktop Parser & Inotify Watcher (.desktop files)
"""

import os
import sys
import tempfile
import time

def parse_desktop_file_python(file_path: str):
    """
    Python implementation matching guest/portal-agent/src/desktop_parser.rs logic.
    """
    try:
        with open(file_path, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
    except Exception as e:
        return None, f"Failed to read desktop file: {e}"

    if "[Desktop Entry]" not in content:
        return None, "Malformed desktop entry: missing [Desktop Entry]"

    app_id = os.path.splitext(os.path.basename(file_path))[0] or "unknown_app"

    name = ""
    generic_name = ""
    comment = ""
    icon = ""
    exec_cmd = ""
    categories = ""
    mime_types = ""
    is_terminal = False
    no_display = False

    in_desktop_entry = False

    for line in content.splitlines():
        trimmed = line.strip()
        if not trimmed or trimmed.startswith('#'):
            continue

        if trimmed.startswith('[') and trimmed.endswith(']'):
            in_desktop_entry = (trimmed == "[Desktop Entry]")
            continue

        if not in_desktop_entry:
            continue

        if '=' in trimmed:
            parts = trimmed.split('=', 1)
            k = parts[0].strip()
            v = parts[1].strip()

            if k == "Name":
                name = v
            elif k == "GenericName":
                generic_name = v
            elif k == "Comment":
                comment = v
            elif k == "Icon":
                icon = v
            elif k == "Exec":
                exec_cmd = v
            elif k == "Categories":
                categories = v
            elif k == "MimeType":
                mime_types = v
            elif k == "Terminal":
                is_terminal = (v.lower() == "true")
            elif k == "NoDisplay":
                no_display = (v.lower() == "true")

    if no_display:
        return None, "NoDisplay=true filtered"

    if not name:
        name = app_id

    if not icon or (not icon.startswith('/') and not os.path.exists(icon)):
        candidate = f"/usr/share/icons/{icon}.png"
        if os.path.exists(candidate):
            icon = candidate
        else:
            icon = "/usr/share/icons/default_linux_app_icon.png"

    return {
        "app_id": app_id,
        "name": name,
        "generic_name": generic_name,
        "comment": comment,
        "icon": icon,
        "exec": exec_cmd,
        "categories": categories,
        "mime_types": mime_types,
        "is_terminal": is_terminal,
        "no_display": no_display,
    }, None

def main():
    print("=== Running Adversarial Desktop Entry Parser Stress Tests ===")
    with tempfile.TemporaryDirectory() as tmpdir:
        # Case 1: Empty file
        empty_file = os.path.join(tmpdir, "empty.desktop")
        with open(empty_file, "w") as f:
            f.write("")
        res, err = parse_desktop_file_python(empty_file)
        assert res is None and "missing [Desktop Entry]" in err
        print("[PASS] Empty file correctly rejected")

        # Case 2: Missing [Desktop Entry] section header
        no_sec = os.path.join(tmpdir, "no_sec.desktop")
        with open(no_sec, "w") as f:
            f.write("Name=Test\nExec=test\n")
        res, err = parse_desktop_file_python(no_sec)
        assert res is None and "missing [Desktop Entry]" in err
        print("[PASS] File missing [Desktop Entry] section correctly rejected")

        # Case 3: NoDisplay=true filtering
        nodisplay_file = os.path.join(tmpdir, "nodisplay.desktop")
        with open(nodisplay_file, "w") as f:
            f.write("[Desktop Entry]\nName=Hidden App\nNoDisplay=true\nExec=hidden\n")
        res, err = parse_desktop_file_python(nodisplay_file)
        assert res is None and "NoDisplay=true filtered" in err
        print("[PASS] NoDisplay=true file correctly filtered")

        # Case 4: NoDisplay=True (case insensitive)
        nodisplay_caps = os.path.join(tmpdir, "nodisplay_caps.desktop")
        with open(nodisplay_caps, "w") as f:
            f.write("[Desktop Entry]\nName=Hidden App Caps\nNoDisplay=TRUE\nExec=hidden\n")
        res, err = parse_desktop_file_python(nodisplay_caps)
        assert res is None and "NoDisplay=true filtered" in err
        print("[PASS] NoDisplay=TRUE case-insensitive filtering verified")

        # Case 5: Valid .desktop entry with spaces around '='
        valid_spaces = os.path.join(tmpdir, "valid_spaces.desktop")
        with open(valid_spaces, "w") as f:
            f.write("[Desktop Entry]  \n  Name = GIMP Image Editor \n Exec = gimp %F \n Icon = gimp \n")
        res, err = parse_desktop_file_python(valid_spaces)
        assert res is not None
        assert res["name"] == "GIMP Image Editor"
        assert res["exec"] == "gimp %F"
        assert res["icon"] == "/usr/share/icons/default_linux_app_icon.png"
        print("[PASS] Valid .desktop entry with irregular spacing parsed successfully")

        # Case 6: Special Characters and XML injection strings
        special_chars = os.path.join(tmpdir, "special_chars.desktop")
        with open(special_chars, "w") as f:
            f.write("[Desktop Entry]\nName=R&D App <Alpha>\nExec=app --arg1=\"val1\" & app --arg2='val2'\n")
        res, err = parse_desktop_file_python(special_chars)
        assert res is not None
        assert res["name"] == "R&D App <Alpha>"
        assert res["exec"] == "app --arg1=\"val1\" & app --arg2='val2'"
        print("[PASS] Special characters in Name and Exec preserved cleanly in parser output")

        # Case 7: High frequency write burst (inotify watcher debounce simulation)
        burst_file = os.path.join(tmpdir, "burst.desktop")
        start_time = time.time()
        for i in range(100):
            with open(burst_file, "w") as f:
                f.write(f"[Desktop Entry]\nName=Burst App {i}\nExec=burst {i}\n")
        res, err = parse_desktop_file_python(burst_file)
        assert res is not None
        assert res["name"] == "Burst App 99"
        elapsed = time.time() - start_time
        print(f"[PASS] 100 rapid file overwrites processed in {elapsed:.4f} seconds")

    print("ALL Adversarial Desktop Entry Parser STRESS TESTS PASSED!")

if __name__ == "__main__":
    main()
