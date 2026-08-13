package android.os;

import java.util.List;
import java.util.ArrayList;

public class Parcel {
    public static Parcel obtain() { return new Parcel(); }
    public void recycle() {}
    public void writeInterfaceToken(String token) {}
    public void enforceInterface(String token) {}
    public void writeString(String val) {}
    public String readString() { return ""; }
    public void writeInt(int val) {}
    public int readInt() { return 0; }
    public void writeLong(long val) {}
    public long readLong() { return 0L; }
    public void writeFloat(float val) {}
    public float readFloat() { return 0.0f; }
    public void writeStrongBinder(IBinder binder) {}
    public void writeStrongInterface(IInterface val) {}
    public IBinder readStrongBinder() { return null; }
    public void writeNoException() {}
    public void readException() {}
    public <T extends Parcelable> void writeTypedList(List<T> val) {}
    public <T extends Parcelable> List<T> createTypedArrayList(Parcelable.Creator<T> c) { return new ArrayList<>(); }
    public <T extends Parcelable> void writeTypedObject(T val, int parcelableFlags) {}
    public <T extends Parcelable> T readTypedObject(Parcelable.Creator<T> c) { return null; }
    public void writeByteArray(byte[] b) {}
    public byte[] createByteArray() { return new byte[0]; }
}
