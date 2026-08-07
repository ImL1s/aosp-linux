package android.os;

public interface Parcelable {
    public static final int PARCELABLE_WRITE_RETURN_VALUE = 0x0001;
    public static final int CONTENTS_FILE_DESCRIPTOR = 0x0001;

    int describeContents();
    void writeToParcel(Parcel dest, int flags);

    public interface Creator<T> {
        T createFromParcel(Parcel in);
        T[] newArray(int size);
    }
}
