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

public final class Slog {
    private Slog() {}

    public static int v(String tag, String msg) { return Log.v(tag, msg); }
    public static int d(String tag, String msg) { return Log.d(tag, msg); }
    public static int i(String tag, String msg) { return Log.i(tag, msg); }
    public static int w(String tag, String msg) { return Log.w(tag, msg); }
    public static int w(String tag, String msg, Throwable tr) { return Log.w(tag, msg, tr); }
    public static int e(String tag, String msg) { return Log.e(tag, msg); }
    public static int e(String tag, String msg, Throwable tr) { return Log.e(tag, msg, tr); }
}
