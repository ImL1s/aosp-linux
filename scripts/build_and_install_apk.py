#!/usr/bin/env python3
import os
import subprocess
import sys
import time

ANDROID_HOME = os.path.expanduser("~/Library/Android/sdk")
BUILD_TOOLS = os.path.join(ANDROID_HOME, "build-tools/35.0.0")
PLATFORM_JAR = os.path.join(ANDROID_HOME, "platforms/android-35/android.jar")
PLATFORM_TOOLS = os.path.join(ANDROID_HOME, "platform-tools")

env = os.environ.copy()
env["PATH"] = f"{PLATFORM_TOOLS}:{BUILD_TOOLS}:{env.get('PATH', '')}"

def run_cmd(cmd, check=True):
    print(f"[CMD] {cmd}")
    res = subprocess.run(cmd, shell=True, env=env, capture_output=True, text=True)
    if res.returncode != 0 and check:
        print(f"[ERR] Command failed (exit {res.returncode}):\n{res.stderr}\n{res.stdout}")
        sys.exit(1)
    return res.stdout

def main():
    workspace = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    out_dir = os.path.join(workspace, "build_out", "apk_build")
    os.makedirs(out_dir, exist_ok=True)
    
    gen_aidl_dir = os.path.join(out_dir, "gen_aidl")
    classes_dir = os.path.join(out_dir, "classes")
    os.makedirs(gen_aidl_dir, exist_ok=True)
    os.makedirs(classes_dir, exist_ok=True)

    print("=== Step 1: Compile AIDL interfaces ===")
    framework_includes = os.path.join(out_dir, "framework_includes")
    os.makedirs(os.path.join(framework_includes, "android", "view"), exist_ok=True)
    os.makedirs(os.path.join(framework_includes, "android", "os"), exist_ok=True)
    os.makedirs(os.path.join(framework_includes, "android", "annotation"), exist_ok=True)
    
    with open(os.path.join(framework_includes, "android", "view", "Surface.aidl"), "w") as f:
        f.write("package android.view;\nparcelable Surface;\n")
        
    with open(os.path.join(framework_includes, "android", "os", "ServiceManager.java"), "w") as f:
        f.write('''package android.os;
public final class ServiceManager {
    public static IBinder getService(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            return (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
        } catch (Exception e) {
            return null;
        }
    }
    public static void addService(String name, IBinder service) {}
}''')

    for ann in ["NonNull", "Nullable", "SystemApi", "SystemService", "RequiresPermission"]:
        with open(os.path.join(framework_includes, "android", "annotation", f"{ann}.java"), "w") as f:
            f.write(f'''package android.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.SOURCE)
public @interface {ann} {{
    String[] value() default {{}};
    int[] intValues() default {{}};
    boolean flag() default false;
}}''')

    with open(os.path.join(framework_includes, "android", "annotation", "IntDef.java"), "w") as f:
        f.write('''package android.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.SOURCE)
public @interface IntDef {
    int[] value() default {};
    String[] prefix() default {};
    boolean flag() default false;
}''')

    aidl_bin = os.path.join(BUILD_TOOLS, "aidl")
    aidl_files = [
        "frameworks/base/core/java/android/system/linux/ILinuxBridge.aidl",
        "frameworks/base/core/java/android/system/linux/ILinuxManager.aidl",
        "frameworks/base/core/java/android/system/linux/ILinuxPortalService.aidl",
        "frameworks/base/core/java/android/system/linux/ILinuxStatusCallback.aidl",
        "frameworks/base/core/java/android/system/linux/ILinuxStorageProvider.aidl",
        "frameworks/base/core/java/android/system/linux/ILinuxTerminalCallback.aidl",
        "frameworks/base/core/java/android/system/linux/ILinuxWindowBridge.aidl",
        "frameworks/base/core/java/android/system/linux/LinuxAppInfo.aidl",
    ]
    for af in aidl_files:
        src = os.path.join(workspace, af)
        if os.path.exists(src) and not af.endswith("LinuxAppInfo.aidl"):
            cmd = f'"{aidl_bin}" -I"{framework_includes}" -I"{workspace}/frameworks/base/core/java" -o"{gen_aidl_dir}" "{src}"'
            run_cmd(cmd)

    print("=== Step 2: aapt2 link Manifest ===")
    src_manifest = os.path.join(workspace, "packages/apps/LinuxTerminal/AndroidManifest.xml")
    manifest = os.path.join(out_dir, "AndroidManifest.xml")
    with open(src_manifest, "r") as f:
        m_content = f.read()
    # Remove sharedUserId for testing on generic emulators that use different platform key
    m_content_no_shared_uid = m_content.replace('android:sharedUserId="android.uid.system"', '')
    with open(manifest, "w") as f:
        f.write(m_content_no_shared_uid)

    res_apk = os.path.join(out_dir, "app-res.apk")
    gen_r_dir = os.path.join(out_dir, "gen_r")
    os.makedirs(gen_r_dir, exist_ok=True)
    
    cmd = f'aapt2 link -I "{PLATFORM_JAR}" --min-sdk-version 34 --target-sdk-version 35 --manifest "{manifest}" --java "{gen_r_dir}" -o "{res_apk}" --auto-add-overlay'
    run_cmd(cmd)

    print("=== Step 3: Compile Java Sources ===")
    java_sources = []
    for root, _, files in os.walk(os.path.join(workspace, "packages/apps/LinuxTerminal/src")):
        for f in files:
            if f.endswith(".java"):
                java_sources.append(os.path.join(root, f))
    for root, _, files in os.walk(os.path.join(workspace, "frameworks/base/core/java/android/system/linux")):
        for f in files:
            if f.endswith(".java"):
                java_sources.append(os.path.join(root, f))
    for root, _, files in os.walk(gen_aidl_dir):
        for f in files:
            if f.endswith(".java"):
                java_sources.append(os.path.join(root, f))
    for root, _, files in os.walk(gen_r_dir):
        for f in files:
            if f.endswith(".java"):
                java_sources.append(os.path.join(root, f))
    for root, _, files in os.walk(framework_includes):
        for f in files:
            if f.endswith(".java"):
                java_sources.append(os.path.join(root, f))

    src_list_file = os.path.join(out_dir, "sources.txt")
    with open(src_list_file, "w") as f:
        for s in java_sources:
            f.write(f'"{s}"\n')

    cmd = f'javac -source 17 -target 17 -cp "{PLATFORM_JAR}" -d "{classes_dir}" @{src_list_file}'
    run_cmd(cmd)

    print("=== Step 4: D8 Dexing ===")
    dex_dir = os.path.join(out_dir, "dex")
    os.makedirs(dex_dir, exist_ok=True)
    class_files = []
    for root, _, files in os.walk(classes_dir):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
    class_list_file = os.path.join(out_dir, "classes.txt")
    with open(class_list_file, "w") as f:
        for c in class_files:
            f.write(f'{c}\n')
    
    cmd = f'd8 --lib "{PLATFORM_JAR}" --output "{dex_dir}" @{class_list_file}'
    run_cmd(cmd)

    print("=== Step 4.5: Compile Native JNI Shared Library (libvterm_jni.so) ===")
    ndk_bin = os.path.join(ANDROID_HOME, "ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin")
    clang_cpp = os.path.join(ndk_bin, "aarch64-linux-android35-clang++")
    clang_c = os.path.join(ndk_bin, "aarch64-linux-android35-clang")
    
    jni_dir = os.path.join(workspace, "packages/apps/LinuxTerminal/jni")
    lib_arm64_dir = os.path.join(out_dir, "lib", "arm64-v8a")
    os.makedirs(lib_arm64_dir, exist_ok=True)
    so_target = os.path.join(lib_arm64_dir, "libvterm_jni.so")
    
    c_srcs = [
        "libvterm/src/vterm.c", "libvterm/src/screen.c", "libvterm/src/state.c",
        "libvterm/src/parser.c", "libvterm/src/pen.c", "libvterm/src/unicode.c",
        "libvterm/src/encoding.c"
    ]
    cpp_srcs = [
        "libvterm_jni.cpp", "terminal_renderer.cpp", "vterm_parser.cpp",
        "sgr_mouse_generator.cpp", "pty_framing_handler.cpp"
    ]
    
    c_objs = []
    for cs in c_srcs:
        src_path = os.path.join(jni_dir, cs)
        obj_path = os.path.join(out_dir, os.path.basename(cs) + ".o")
        cmd = f'"{clang_c}" -c -O2 -fPIC -I"{jni_dir}" -I"{jni_dir}/libvterm/include" "{src_path}" -o "{obj_path}"'
        run_cmd(cmd)
        c_objs.append(obj_path)
        
    cpp_objs = []
    for cpps in cpp_srcs:
        src_path = os.path.join(jni_dir, cpps)
        obj_path = os.path.join(out_dir, os.path.basename(cpps) + ".o")
        cmd = f'"{clang_cpp}" -c -std=c++20 -O2 -fPIC -fexceptions -I"{jni_dir}" -I"{jni_dir}/libvterm/include" "{src_path}" -o "{obj_path}"'
        run_cmd(cmd)
        cpp_objs.append(obj_path)
        
    all_objs = " ".join(f'"{o}"' for o in c_objs + cpp_objs)
    cmd = f'"{clang_cpp}" -shared -static-libstdc++ -o "{so_target}" {all_objs} -llog -landroid -ljnigraphics'
    run_cmd(cmd)

    print("=== Step 5: Package & Sign APK ===")
    unaligned_apk = os.path.join(out_dir, "unaligned.apk")
    final_apk = os.path.join(workspace, "build_out", "LinuxTerminal.apk")
    import shutil
    shutil.copyfile(res_apk, unaligned_apk)
    
    # Add classes.dex and lib/arm64-v8a/*.so to apk
    cmd = f'cd "{dex_dir}" && zip -u "{unaligned_apk}" classes.dex'
    run_cmd(cmd)
    
    cmd = f'cd "{out_dir}" && zip -u -r "{unaligned_apk}" lib'
    run_cmd(cmd)

    aligned_apk = os.path.join(out_dir, "aligned.apk")
    cmd = f'zipalign -f -p 4 "{unaligned_apk}" "{aligned_apk}"'
    run_cmd(cmd)

    # Generate debug keystore if not exist
    keystore = os.path.join(out_dir, "debug.keystore")
    if not os.path.exists(keystore):
        cmd = f'keytool -genkey -v -keystore "{keystore}" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"'
        run_cmd(cmd)

    cmd = f'apksigner sign --ks "{keystore}" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out "{final_apk}" "{aligned_apk}"'
    run_cmd(cmd)

    print(f"=== APK Built Successfully: {final_apk} ===")

    print("=== Step 6: Waiting for Emulator ===")
    run_cmd("adb wait-for-device")
    
    # Wait for sys.boot_completed
    print("Waiting for Android OS to finish boot...")
    for i in range(60):
        out = run_cmd("adb shell getprop sys.boot_completed", check=False).strip()
        if out == "1":
            print(f"Android Boot Completed in ~{i*2} seconds!")
            break
        time.sleep(2)

    print("=== Step 7: Install APK to Emulator ===")
    run_cmd(f'adb install -r -t -g "{final_apk}"')

    print("=== Step 8: Launch Terminal Activity ===")
    run_cmd("adb shell am start -n com.android.virtualization.terminal/.TerminalActivity")
    time.sleep(3)

    print("=== Step 9: Verify Running Top Activity ===")
    top_act = run_cmd("adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")
    print(f"Top Activity Output:\n{top_act}")

    screenshot_path = os.path.join(workspace, "build_out", "emulator_terminal_screenshot.png")
    run_cmd(f"adb exec-out screencap -p > \"{screenshot_path}\"")
    print(f"=== Screenshot Captured at: {screenshot_path} ===")

if __name__ == "__main__":
    main()
