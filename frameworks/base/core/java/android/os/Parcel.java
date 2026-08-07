package android.os;

import java.util.ArrayList;
import java.util.List;

public class Parcel {
    private String mData = "";

    public static Parcel obtain() { return new Parcel(); }
    public void recycle() {}

    public void writeString(String val) {}
    public String readString() { return ""; }
    public void writeInt(int val) {}
    public int readInt() { return 0; }
    public void writeLong(long val) {}
    public long readLong() { return 0L; }
    public void writeFloat(float val) {}
    public float readFloat() { return 0.0f; }
    public void writeBoolean(boolean val) {}
    public boolean readBoolean() { return false; }

    public void writeStringList(List<String> val) {}
    public ArrayList<String> createStringArrayList() { return new ArrayList<>(); }
    public void readStringList(List<String> val) {}
}
