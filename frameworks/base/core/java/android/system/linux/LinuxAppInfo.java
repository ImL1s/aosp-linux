package android.system.linux;

import android.os.Parcel;
import android.os.Parcelable;

public final class LinuxAppInfo implements Parcelable {
    private final String appId;
    private final String name;
    private final String execCommand;
    private final String iconPath;
    private final String mimeTypes;

    public LinuxAppInfo(
            String appId,
            String name,
            String execCommand,
            String iconPath,
            String mimeTypes) {
        this.appId = appId;
        this.name = name;
        this.execCommand = execCommand;
        this.iconPath = iconPath;
        this.mimeTypes = mimeTypes;
    }

    protected LinuxAppInfo(Parcel in) {
        appId = in.readString();
        name = in.readString();
        execCommand = in.readString();
        iconPath = in.readString();
        mimeTypes = in.readString();
    }

    public String getAppId() {
        return appId;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return name;
    }

    public String getExecCommand() {
        return execCommand;
    }

    public String getIconPath() {
        return iconPath;
    }

    public String getMimeTypes() {
        return mimeTypes;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(appId);
        dest.writeString(name);
        dest.writeString(execCommand);
        dest.writeString(iconPath);
        dest.writeString(mimeTypes);
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
