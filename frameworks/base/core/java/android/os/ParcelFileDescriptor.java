package android.os;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ParcelFileDescriptor implements Parcelable, Closeable {
    public static final int MODE_READ_ONLY = 0x10000000;
    public static final int MODE_WRITE_ONLY = 0x20000000;
    public static final int MODE_READ_WRITE = 0x30000000;
    public static final int MODE_CREATE = 0x08000000;
    public static final int MODE_TRUNCATE = 0x04000000;
    public static final int MODE_APPEND = 0x02000000;

    private final FileDescriptor mFd;

    public ParcelFileDescriptor() {
        mFd = new FileDescriptor();
    }

    public ParcelFileDescriptor(FileDescriptor fd) {
        mFd = fd;
    }

    public static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
        if (file == null) {
            throw new FileNotFoundException("Null file");
        }
        return new ParcelFileDescriptor(new FileDescriptor());
    }

    public FileDescriptor getFileDescriptor() {
        return mFd;
    }

    @Override
    public int describeContents() { return CONTENTS_FILE_DESCRIPTOR; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {}

    @Override
    public void close() throws IOException {}

    public static final Creator<ParcelFileDescriptor> CREATOR = new Creator<ParcelFileDescriptor>() {
        @Override
        public ParcelFileDescriptor createFromParcel(Parcel in) {
            return new ParcelFileDescriptor(new FileDescriptor());
        }

        @Override
        public ParcelFileDescriptor[] newArray(int size) {
            return new ParcelFileDescriptor[size];
        }
    };
}

