package android.system.linux;

import android.os.Parcel;
import android.os.Parcelable;

public class LinuxAppInfo implements Parcelable {
    public String appId;
    public String name;
    public String iconPath;
    public String execCmd;

    public LinuxAppInfo() {}

    public LinuxAppInfo(String appId, String name, String iconPath, String execCmd) {
        this.appId = appId;
        this.name = name;
        this.iconPath = iconPath;
        this.execCmd = execCmd;
    }

    protected LinuxAppInfo(Parcel in) {
        appId = in.readString();
        name = in.readString();
        iconPath = in.readString();
        execCmd = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(appId);
        dest.writeString(name);
        dest.writeString(iconPath);
        dest.writeString(execCmd);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<LinuxAppInfo> CREATOR = new Creator<LinuxAppInfo>() {
        @Override
        public LinuxAppInfo createFromParcel(Parcel in) {
            return new LinuxAppInfo(in);
        }

        @Override
        public LinuxAppInfo[] newArray(int size) {
            return new LinuxAppInfo[size];
        }
    };
}
