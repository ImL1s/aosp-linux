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
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;

/**
 * Base class for system services lifecycle management.
 * {@hide}
 */
public abstract class SystemService {
    public static final int PHASE_SYSTEM_SERVICES_READY = 500;
    public static final int PHASE_ACTIVITY_MANAGER_READY = 550;
    public static final int PHASE_THIRD_PARTY_APPS_CAN_START = 600;
    public static final int PHASE_BOOT_COMPLETED = 1000;

    public static final class TargetUser {
        private final int mUserId;
        public TargetUser(int userId) { mUserId = userId; }
        public int getUserIdentifier() { return mUserId; }
        public UserHandle getUserHandle() { return UserHandle.of(mUserId); }
    }

    private final Context mContext;

    public SystemService(Context context) {
        mContext = context;
    }

    public final Context getContext() {
        return mContext;
    }

    public abstract void onStart();

    public void onBootPhase(int phase) {}

    public void onUserUnlocking(TargetUser user) {}

    public void onUserUnlocked(TargetUser user) {}

    public void onUserUnlocked(int userId) {}

    protected final void publishBinderService(String name, IBinder service) {
        publishBinderService(name, service, false);
    }

    protected final void publishBinderService(String name, IBinder service, boolean allowIsolated) {
        ServiceManager.addService(name, service, allowIsolated);
    }

    protected final <T> void publishLocalService(Class<T> type, T service) {
        LocalServices.addService(type, service);
    }
}
