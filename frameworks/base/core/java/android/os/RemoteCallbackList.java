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

import java.util.ArrayList;
import java.util.List;

/**
 * Takes care of the boilerplate involved with maintaining a list of {@link IInterface} callbacks.
 * {@hide}
 */
public class RemoteCallbackList<E extends IInterface> {
    private final List<CallbackRecord> mCallbacks = new ArrayList<>();
    private Object[] mActiveBroadcast;
    private int mBroadcastCount = -1;

    private final class CallbackRecord implements IBinder.DeathRecipient {
        final E mCallback;
        final Object mCookie;

        CallbackRecord(E callback, Object cookie) {
            mCallback = callback;
            mCookie = cookie;
        }

        @Override
        public void binderDied() {
            synchronized (mCallbacks) {
                mCallbacks.remove(this);
            }
            onCallbackDied(mCallback, mCookie);
        }
    }

    public boolean register(E callback) {
        return register(callback, null);
    }

    public boolean register(E callback, Object cookie) {
        if (callback == null) return false;
        synchronized (mCallbacks) {
            IBinder binder = callback.asBinder();
            if (binder == null) return false;
            for (CallbackRecord record : mCallbacks) {
                if (record.mCallback.asBinder().equals(binder)) {
                    return true;
                }
            }
            CallbackRecord record = new CallbackRecord(callback, cookie);
            try {
                binder.linkToDeath(record, 0);
            } catch (RemoteException e) {
                return false;
            }
            mCallbacks.add(record);
            return true;
        }
    }

    public boolean unregister(E callback) {
        if (callback == null) return false;
        synchronized (mCallbacks) {
            IBinder binder = callback.asBinder();
            if (binder == null) return false;
            for (int i = 0; i < mCallbacks.size(); i++) {
                CallbackRecord record = mCallbacks.get(i);
                if (record.mCallback.asBinder().equals(binder)) {
                    binder.unlinkToDeath(record, 0);
                    mCallbacks.remove(i);
                    return true;
                }
            }
            return false;
        }
    }

    public void kill() {
        synchronized (mCallbacks) {
            for (CallbackRecord record : mCallbacks) {
                record.mCallback.asBinder().unlinkToDeath(record, 0);
            }
            mCallbacks.clear();
        }
    }

    public void onCallbackDied(E callback, Object cookie) {}

    public int beginBroadcast() {
        synchronized (mCallbacks) {
            if (mBroadcastCount > 0) {
                throw new IllegalStateException("beginBroadcast() called while already broadcasting");
            }
            int N = mCallbacks.size();
            mBroadcastCount = N;
            if (N <= 0) {
                return 0;
            }
            if (mActiveBroadcast == null || mActiveBroadcast.length < N) {
                mActiveBroadcast = new Object[N];
            }
            for (int i = 0; i < N; i++) {
                mActiveBroadcast[i] = mCallbacks.get(i);
            }
            return N;
        }
    }

    @SuppressWarnings("unchecked")
    public E getBroadcastItem(int index) {
        return ((CallbackRecord) mActiveBroadcast[index]).mCallback;
    }

    public Object getBroadcastCookie(int index) {
        return ((CallbackRecord) mActiveBroadcast[index]).mCookie;
    }

    public void finishBroadcast() {
        synchronized (mCallbacks) {
            if (mBroadcastCount < 0) {
                throw new IllegalStateException("finishBroadcast() called without beginBroadcast()");
            }
            if (mActiveBroadcast != null) {
                int N = mBroadcastCount;
                for (int i = 0; i < N; i++) {
                    mActiveBroadcast[i] = null;
                }
            }
            mBroadcastCount = -1;
        }
    }

    public int getRegisteredCallbackCount() {
        synchronized (mCallbacks) {
            return mCallbacks.size();
        }
    }
}
