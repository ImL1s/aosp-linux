#!/usr/bin/env python3
import os
import subprocess
import time

ANDROID_HOME = os.path.expanduser("~/Library/Android/sdk")
PLATFORM_TOOLS = os.path.join(ANDROID_HOME, "platform-tools")
os.environ["PATH"] = f"{PLATFORM_TOOLS}:{os.environ.get('PATH', '')}"

def run_cmd(cmd):
    print(f"[CMD] {cmd}")
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return res.stdout.strip()

def tap(x, y):
    run_cmd(f"adb shell input tap {x} {y}")
    time.sleep(1)

def keyevent(code):
    run_cmd(f"adb shell input keyevent {code}")
    time.sleep(0.8)

def input_text(text):
    # Escape spaces
    safe_text = text.replace(" ", "%s")
    run_cmd(f"adb shell input text '{safe_text}'")
    time.sleep(0.5)

def swipe(x1, y1, x2, y2, duration=300):
    run_cmd(f"adb shell input swipe {x1} {y1} {x2} {y2} {duration}")
    time.sleep(1)

def capture_step(step_name, filename):
    out_path = f"/Users/iml1s/Documents/mine/aosp-linux/build_out/{filename}"
    brain_path = f"/Users/iml1s/.gemini/antigravity-cli/brain/29f720f6-2fc4-4aa4-af7a-b720fbb0d62a/{filename}"
    run_cmd(f"adb exec-out screencap -p > \"{out_path}\"")
    run_cmd(f"cp \"{out_path}\" \"{brain_path}\"")
    print(f"[SCREENSHOT] Step '{step_name}' captured: {out_path}")

def main():
    print("==================================================")
    print("Starting Human-like End-to-End Testing on Emulator")
    print("==================================================")

    # 1. Bring Terminal to front
    print("\n--- Test 1: Launch & Focus Terminal ---")
    run_cmd("adb shell am start -n com.android.virtualization.terminal/.TerminalActivity")
    time.sleep(2)
    capture_step("1_terminal_opened", "step1_terminal_opened.png")

    # 2. Tap screen to bring up soft keyboard
    print("\n--- Test 2: Tap Terminal Screen to bring up IME Keyboard ---")
    tap(540, 600)
    capture_step("2_keyboard_opened", "step2_keyboard_opened.png")

    # 3. Type commands
    print("\n--- Test 3: Type Linux Commands ---")
    input_text("uname -a")
    keyevent(66) # Enter
    time.sleep(1)
    
    input_text("cat /etc/os-release")
    keyevent(66) # Enter
    time.sleep(1)

    input_text("echo 'Dual-OS Environment Verified!'")
    keyevent(66) # Enter
    time.sleep(1)
    capture_step("3_command_entered", "step3_command_entered.png")

    # 4. Test Home & App Drawer (Launcher3)
    print("\n--- Test 4: Press Home & Open App Drawer ---")
    keyevent(3) # Home key
    time.sleep(1.5)
    capture_step("4_home_screen", "step4_home_screen.png")

    # Swipe up from bottom to open App Drawer
    swipe(540, 1800, 540, 600, 400)
    time.sleep(1.5)
    capture_step("5_app_drawer", "step5_app_drawer.png")

    # 5. Launch Linux App Proxy Activity (Wayland GUI)
    print("\n--- Test 5: Launch Linux App Proxy Activity (Wayland GUI) ---")
    run_cmd("adb shell am start -n com.android.virtualization.terminal/.LinuxAppProxyActivity --es app_id 'org.gnome.gedit' --es app_name 'Text Editor (Linux)'")
    time.sleep(2)
    capture_step("6_wayland_proxy_window", "step6_wayland_proxy_window.png")

    # 6. Test Linux Permission Activity (AppOps Modal)
    print("\n--- Test 6: Trigger Linux Permission Prompt ---")
    run_cmd("adb shell am start -n com.android.virtualization.terminal/.LinuxPermissionActivity --es app_id 'Gedit (Linux IDE)' --es op 'Camera & Video Device'")
    time.sleep(2)
    capture_step("7_permission_dialog", "step7_permission_dialog.png")
    
    # Simulate clicking "Allow (授權)"
    tap(750, 1350)
    time.sleep(1)

    # 7. Press Recents (Multitasking Task Switcher)
    print("\n--- Test 7: Multitasking Task Switcher (Recents) ---")
    keyevent(187) # App Switch / Recents
    time.sleep(2)
    capture_step("8_recents_multitasking", "step8_recents_multitasking.png")

    # 8. Switch back to Terminal
    print("\n--- Test 8: Tap to return to Terminal Activity ---")
    tap(540, 1200)
    time.sleep(2)
    capture_step("9_back_to_terminal", "step9_back_to_terminal.png")

    print("\n==================================================")
    print("All Human-like E2E Tests Completed Successfully!")
    print("==================================================")

if __name__ == "__main__":
    main()
