package android.net;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class LocalSocket {
    private Socket mSocket;

    public LocalSocket() {}

    public void connect(LocalSocketAddress endpoint) throws IOException {
    }

    public InputStream getInputStream() throws IOException {
        return new java.io.ByteArrayInputStream(new byte[0]);
    }

    public OutputStream getOutputStream() throws IOException {
        return new java.io.ByteArrayOutputStream();
    }

    public void close() throws IOException {
        if (mSocket != null) {
            mSocket.close();
        }
    }

    public FileDescriptor getFileDescriptor() {
        return new FileDescriptor();
    }
}
