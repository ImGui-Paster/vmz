package top.niunaijun.blackbox.entity;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;



public class AppConfig implements Parcelable {
    public static final String KEY = "BlackBox_client_config";

    public String packageName;
    public String processName;
    public int bpid;
    public int buid;
    public int uid;
    public int userId;
    public int callingBUid;
    public IBinder token;

    /**
     * Effective root-emulation state for this virtual process, resolved on the
     * server side before the process is started.
     *
     * Shipping it inside AppConfig is deliberate: the virtual process must NOT
     * perform synchronous binder calls from its main thread during application
     * binding to learn whether root is emulated. Doing so previously caused a
     * frozen black screen (main thread blocked inside IOCore#enableRedirect
     * while the server process was itself busy starting the process).
     */
    public boolean rootEnabled;
    public boolean overlayGranted;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.packageName);
        dest.writeString(this.processName);
        dest.writeInt(this.bpid);
        dest.writeInt(this.buid);
        dest.writeInt(this.uid);
        dest.writeInt(this.userId);
        dest.writeInt(this.callingBUid);
        dest.writeStrongBinder(token);
        dest.writeByte(this.rootEnabled ? (byte) 1 : (byte) 0);
        dest.writeByte(this.overlayGranted ? (byte) 1 : (byte) 0);
    }

    public AppConfig() {
    }

    protected AppConfig(Parcel in) {
        this.packageName = in.readString();
        this.processName = in.readString();
        this.bpid = in.readInt();
        this.buid = in.readInt();
        this.uid = in.readInt();
        this.userId = in.readInt();
        this.callingBUid = in.readInt();
        this.token = in.readStrongBinder();
        this.rootEnabled = in.readByte() != 0;
        this.overlayGranted = in.readByte() != 0;
    }

    public static final Parcelable.Creator<AppConfig> CREATOR = new Parcelable.Creator<AppConfig>() {
        @Override
        public AppConfig createFromParcel(Parcel source) {
            return new AppConfig(source);
        }

        @Override
        public AppConfig[] newArray(int size) {
            return new AppConfig[size];
        }
    };
}
