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

package android.system.linux;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * Java stub interface corresponding to ILinuxBridgeDaemon.aidl.
 * {@hide}
 */
public interface ILinuxBridgeDaemon extends IInterface {

    boolean initializeBridge(int vsockCid, String sessionToken) throws RemoteException;
    boolean shutdownBridge() throws RemoteException;
    boolean sendVmControlCommand(int commandId, byte[] payload) throws RemoteException;
    ParcelFileDescriptor openPtyChannel(String sessionId, int width, int height) throws RemoteException;
    void resizePtyChannel(String sessionId, int width, int height) throws RemoteException;
    void writePtyData(String sessionId, byte[] data) throws RemoteException;
    void closePtyChannel(String sessionId) throws RemoteException;
    boolean isGuestConnected() throws RemoteException;

    public abstract static class Stub extends Binder implements ILinuxBridgeDaemon {
        private static final String DESCRIPTOR = "android.system.linux.ILinuxBridgeDaemon";

        public Stub() {
            super(DESCRIPTOR);
        }

        public static ILinuxBridgeDaemon asInterface(IBinder obj) {
            if (obj == null) return null;
            if (obj instanceof ILinuxBridgeDaemon) return (ILinuxBridgeDaemon) obj;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        private static class Proxy implements ILinuxBridgeDaemon {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public boolean initializeBridge(int vsockCid, String sessionToken) throws RemoteException {
                return true;
            }

            @Override
            public boolean shutdownBridge() throws RemoteException {
                return true;
            }

            @Override
            public boolean sendVmControlCommand(int commandId, byte[] payload) throws RemoteException {
                return true;
            }

            @Override
            public ParcelFileDescriptor openPtyChannel(String sessionId, int width, int height) throws RemoteException {
                return null;
            }

            @Override
            public void resizePtyChannel(String sessionId, int width, int height) throws RemoteException {}

            @Override
            public void writePtyData(String sessionId, byte[] data) throws RemoteException {}

            @Override
            public void closePtyChannel(String sessionId) throws RemoteException {}

            @Override
            public boolean isGuestConnected() throws RemoteException {
                return true;
            }
        }
    }
}
