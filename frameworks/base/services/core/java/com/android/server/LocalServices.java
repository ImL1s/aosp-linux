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

import java.util.HashMap;
import java.util.Map;

/**
 * Service registry for system-server internal local services.
 * {@hide}
 */
public final class LocalServices {
    private static final Map<Class<?>, Object> sLocalServiceObjects = new HashMap<>();

    private LocalServices() {}

    @SuppressWarnings("unchecked")
    public static <T> T getService(Class<T> type) {
        synchronized (sLocalServiceObjects) {
            return (T) sLocalServiceObjects.get(type);
        }
    }

    public static <T> void addService(Class<T> type, T service) {
        synchronized (sLocalServiceObjects) {
            sLocalServiceObjects.put(type, service);
        }
    }

    public static <T> void removeServiceForTest(Class<T> type) {
        synchronized (sLocalServiceObjects) {
            sLocalServiceObjects.remove(type);
        }
    }

    public static void clearForTest() {
        synchronized (sLocalServiceObjects) {
            sLocalServiceObjects.clear();
        }
    }
}
