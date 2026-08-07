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

package android.os;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides access to system services registered with ServiceManager.
 * {@hide}
 */
public final class ServiceManager {
    private static final Map<String, IBinder> sServices = new HashMap<>();

    private ServiceManager() {}

    public static IBinder getService(String name) {
        synchronized (sServices) {
            return sServices.get(name);
        }
    }

    public static void addService(String name, IBinder service) {
        addService(name, service, false);
    }

    public static void addService(String name, IBinder service, boolean allowIsolated) {
        synchronized (sServices) {
            sServices.put(name, service);
        }
    }

    public static String[] listServices() {
        synchronized (sServices) {
            return sServices.keySet().toArray(new String[0]);
        }
    }

    public static void clearForTest() {
        synchronized (sServices) {
            sServices.clear();
        }
    }
}
