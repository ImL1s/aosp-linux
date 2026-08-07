/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (Compliance);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.content.Context;
import android.util.Slog;
import com.android.server.linux.LinuxManagerService;
import com.android.server.linux.LinuxPortalService;
import com.android.server.linux.LinuxAudioPolicyHandler;

/**
 * Main entry point for Android SystemServer services initialization.
 * {@hide}
 */
public final class SystemServer {
    private static final String TAG = "SystemServer";

    private final Context mSystemContext;
    private LinuxManagerService mLinuxManagerService;
    private LinuxPortalService mLinuxPortalService;
    private LinuxAudioPolicyHandler mLinuxAudioPolicyHandler;

    public SystemServer(Context systemContext) {
        mSystemContext = systemContext;
    }

    public void startOtherServices() {
        Slog.i(TAG, "StartLinuxManagerService");
        mLinuxManagerService = new LinuxManagerService(mSystemContext);
        mLinuxManagerService.onStart();

        Slog.i(TAG, "StartLinuxPortalService & LinuxAudioPolicyHandler");
        mLinuxPortalService = new LinuxPortalService(mSystemContext);
        mLinuxAudioPolicyHandler = new LinuxAudioPolicyHandler(mSystemContext);

        mLinuxManagerService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mLinuxManagerService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
    }

    public LinuxManagerService getLinuxManagerService() {
        return mLinuxManagerService;
    }

    public LinuxPortalService getLinuxPortalService() {
        return mLinuxPortalService;
    }

    public LinuxAudioPolicyHandler getLinuxAudioPolicyHandler() {
        return mLinuxAudioPolicyHandler;
    }
}
