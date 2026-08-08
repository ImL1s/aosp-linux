import os

tiers = [
    "tests/e2e/tier1_feature_coverage",
    "tests/e2e/tier2_boundary_corner",
    "tests/e2e/tier3_cross_feature",
    "tests/e2e/tier4_real_world",
    "tests/e2e/framework",
    "tests/e2e"
]

candidates = [
    "runner.py", "mock_env.py", "__init__.py", "conftest.py",
    "test_camera.py", "test_audio.py", "test_binder.py", "test_selinux.py",
    "test_hal.py", "test_graphics.py", "test_display.py", "test_sensors.py",
    "test_wifi.py", "test_bluetooth.py", "test_storage.py", "test_input.py",
    "test_power.py", "test_v4l2.py", "test_alsa.py", "test_drm.py",
    "test_property.py", "test_init.py", "test_logd.py", "test_servicemanager.py",
    "test_surfaceflinger.py", "test_audioflinger.py", "test_camera_service.py",
    "test_mediaserver.py", "test_keystore.py", "test_tombstone.py", "test_anr.py",
    "test_boundary.py", "test_corner.py", "test_cross_feature.py", "test_real_world.py",
    "test_performance.py", "test_concurrency.py", "test_ipc.py"
]

base = "/Users/iml1s/Documents/mine/aosp-linux"

for t in tiers:
    print(f"=== {t} ===")
    for c in candidates:
        p = os.path.join(base, t, c)
        try:
            with open(p, "r") as f:
                data = f.read()
                print(f"READABLE ({len(data)}b): {t}/{c}")
        except FileNotFoundError:
            pass
        except Exception as e:
            print(f"PERM_ERR ({e}): {t}/{c}")
