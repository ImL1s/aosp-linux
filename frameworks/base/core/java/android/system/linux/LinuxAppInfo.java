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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Representation of a Linux desktop application synchronized from the guest Debian system (.desktop entry).
 * @hide
 */
@SystemApi
public final class LinuxAppInfo implements Parcelable {

    private final String mAppId;
    private final String mDisplayName;
    private final String mGenericName;
    private final String mComment;
    private final String mIconPath;
    private final String mExecCommand;
    private final List<String> mMimeTypes;
    private final List<String> mCategories;
    private final boolean mIsTerminalApp;

    public LinuxAppInfo(
            @NonNull String appId,
            @NonNull String displayName,
            @Nullable String genericName,
            @Nullable String comment,
            @Nullable String iconPath,
            @NonNull String execCommand,
            @Nullable List<String> mimeTypes,
            @Nullable List<String> categories,
            boolean isTerminalApp) {
        this.mAppId = Objects.requireNonNull(appId, "appId must not be null");
        this.mDisplayName = Objects.requireNonNull(displayName, "displayName must not be null");
        this.mGenericName = genericName != null ? genericName : "";
        this.mComment = comment != null ? comment : "";
        this.mIconPath = iconPath != null ? iconPath : "";
        this.mExecCommand = Objects.requireNonNull(execCommand, "execCommand must not be null");
        this.mMimeTypes = mimeTypes != null ? Collections.unmodifiableList(new ArrayList<>(mimeTypes)) : Collections.emptyList();
        this.mCategories = categories != null ? Collections.unmodifiableList(new ArrayList<>(categories)) : Collections.emptyList();
        this.mIsTerminalApp = isTerminalApp;
    }

    public LinuxAppInfo(@NonNull String appId, @NonNull String displayName,
                        @NonNull String execCommand, @Nullable String iconPath,
                        @Nullable String mimeTypes) {
        this(appId, displayName, "", "", iconPath, execCommand,
             mimeTypes != null ? Collections.singletonList(mimeTypes) : null,
             null, false);
    }

    private LinuxAppInfo(Parcel in) {
        mAppId = in.readString();
        mDisplayName = in.readString();
        mGenericName = in.readString();
        mComment = in.readString();
        mIconPath = in.readString();
        mExecCommand = in.readString();
        mMimeTypes = in.createStringArrayList();
        mCategories = in.createStringArrayList();
        mIsTerminalApp = in.readBoolean();
    }

    @NonNull
    public String getAppId() { return mAppId; }

    @NonNull
    public String getDisplayName() { return mDisplayName; }

    @NonNull
    public String getGenericName() { return mGenericName; }

    @NonNull
    public String getComment() { return mComment; }

    @NonNull
    public String getIconPath() { return mIconPath; }

    @NonNull
    public String getExecCommand() { return mExecCommand; }

    @NonNull
    public List<String> getMimeTypes() { return mMimeTypes; }

    @NonNull
    public List<String> getCategories() { return mCategories; }

    public boolean isTerminalApp() { return mIsTerminalApp; }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mAppId);
        dest.writeString(mDisplayName);
        dest.writeString(mGenericName);
        dest.writeString(mComment);
        dest.writeString(mIconPath);
        dest.writeString(mExecCommand);
        dest.writeStringList(mMimeTypes);
        dest.writeStringList(mCategories);
        dest.writeBoolean(mIsTerminalApp);
    }

    public static final @NonNull Creator<LinuxAppInfo> CREATOR = new Creator<LinuxAppInfo>() {
        @Override
        public LinuxAppInfo createFromParcel(Parcel in) {
            return new LinuxAppInfo(in);
        }

        @Override
        public LinuxAppInfo[] newArray(int size) {
            return new LinuxAppInfo[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LinuxAppInfo that = (LinuxAppInfo) o;
        return mIsTerminalApp == that.mIsTerminalApp &&
                Objects.equals(mAppId, that.mAppId) &&
                Objects.equals(mDisplayName, that.mDisplayName) &&
                Objects.equals(mExecCommand, that.mExecCommand);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAppId, mDisplayName, mExecCommand, mIsTerminalApp);
    }

    @Override
    public String toString() {
        return "LinuxAppInfo{" +
                "appId='" + mAppId + '\'' +
                ", displayName='" + mDisplayName + '\'' +
                ", execCommand='" + mExecCommand + '\'' +
                ", isTerminalApp=" + mIsTerminalApp +
                '}';
    }
}
