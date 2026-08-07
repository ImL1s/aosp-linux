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

package android.util;

public final class Log {
    private Log() {}

    public static int v(String tag, String msg) {
        System.out.println("V/" + tag + ": " + msg);
        return 0;
    }

    public static int d(String tag, String msg) {
        System.out.println("D/" + tag + ": " + msg);
        return 0;
    }

    public static int i(String tag, String msg) {
        System.out.println("I/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println("W/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        System.out.println("W/" + tag + ": " + msg + " Exception: " + tr);
        return 0;
    }

    public static int e(String tag, String msg) {
        System.err.println("E/" + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        System.err.println("E/" + tag + ": " + msg + " Exception: " + tr);
        return 0;
    }
}
