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

package android.app;

import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;
import android.system.linux.ILinuxManager;
import android.system.linux.LinuxManager;

import java.util.HashMap;
import java.util.Map;

/**
 * SystemServiceRegistry integration for registering LinuxManager system service fetcher.
 * @hide
 */
public final class SystemServiceRegistry {

    private static final Map<String, ServiceFetcher<?>> SYSTEM_SERVICE_FETCHERS = new HashMap<>();

    public interface ServiceFetcher<T> {
        T getService(Context ctx);
    }

    static {
        SYSTEM_SERVICE_FETCHERS.put(Context.LINUX_SERVICE, new ServiceFetcher<LinuxManager>() {
            @Override
            public LinuxManager getService(Context ctx) {
                IBinder b = ServiceManager.getService(Context.LINUX_SERVICE);
                if (b == null) {
                    return null;
                }
                return new LinuxManager(ctx, ILinuxManager.Stub.asInterface(b));
            }
        });
    }

    public static Object getSystemService(Context ctx, String name) {
        ServiceFetcher<?> fetcher = SYSTEM_SERVICE_FETCHERS.get(name);
        return fetcher != null ? fetcher.getService(ctx) : null;
    }

    public static void registerService(String serviceName, Class<?> serviceClass, ServiceFetcher<?> serviceFetcher) {
        SYSTEM_SERVICE_FETCHERS.put(serviceName, serviceFetcher);
    }
}
