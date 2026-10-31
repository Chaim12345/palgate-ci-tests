package com.bluegate.shared.data.types;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Parcel-compatible stand-in for the official PalGate app's
 * com.bluegate.shared.data.types.Device.
 *
 * The PalGate home-screen widget receivers (PalWidget / PalWidgetLarge,
 * exported=true) accept an "open gate" broadcast that carries a Device
 * Parcelable in its extras. We cannot instantiate their class, but Parcel
 * streams are self-describing: the framework writes getClass().getName()
 * and our writeToParcel bytes; the receiving app then loads THAT class name
 * with ITS OWN class loader — which resolves to the real Device class in
 * the PalGate APK — and its CREATOR replays the 40 fields below.
 *
 * The field order MUST match the official app's writeToParcel exactly
 * (verified against the 1.6.0076 smali: 40 reads == 40 writes, 1:1).
 *
 * Boolean encoding (matches their readByte/Boolean.valueOf pattern):
 *   0 = null, 1 = true, 2 = false. Fields their open path dereferences
 *   with Boolean.booleanValue() must never be null, so we default every
 *   Boolean to a non-null value.
 */
public class Device implements Parcelable {

    public String address;
    public Boolean admin;
    public String id;
    public String key;
    public String macAddress;
    public String model;
    public String name1;
    public String name2;
    public String customName1;
    public String customName2;
    public String displayName;
    public Boolean output1;
    public Boolean output2;
    public String relay1;
    public String relay2;
    public Boolean output1LatchStatus;
    public Boolean output2LatchStatus;
    public Boolean output1Latch;
    public Boolean output2Latch;
    public Boolean output1Disabled;
    public Boolean output2Disabled;
    public String output1Icon;
    public String output1Color;
    public String output2Icon;
    public String output2Color;
    public int gateIndex;
    public String validUntil;
    public Boolean notifications;
    public String callGroupId;
    public int callOrder;
    public Boolean googleAssistantActive;
    public String output1GoogleAssistantChallenge;
    public String output2GoogleAssistantChallenge;
    public String groupId;
    public Boolean localOnly;
    public String simStatus;
    public Parcelable geoFence1;   // GeoFence in their app; null is fine
    public Parcelable geoFence2;
    public Boolean faceRecEnableByUser;
    public Boolean faceRecEnableByAdmin;

    public Device() {
        // Non-null defaults for every Boolean the open path dereferences.
        admin = Boolean.FALSE;
        output1 = Boolean.TRUE;
        output2 = Boolean.FALSE;
        output1LatchStatus = Boolean.FALSE;
        output2LatchStatus = Boolean.FALSE;
        output1Latch = Boolean.FALSE;
        output2Latch = Boolean.FALSE;
        output1Disabled = Boolean.FALSE;
        output2Disabled = Boolean.FALSE;
        notifications = Boolean.TRUE;
        googleAssistantActive = Boolean.FALSE;
        localOnly = Boolean.FALSE;
        faceRecEnableByUser = Boolean.FALSE;
        faceRecEnableByAdmin = Boolean.FALSE;
    }

    private static void putBool(Parcel p, Boolean b) {
        if (b == null) p.writeByte((byte) 0);
        else p.writeByte((byte) (b ? 1 : 2));
    }

    @Override
    public void writeToParcel(Parcel p, int flags) {
        p.writeString(address);
        putBool(p, admin);
        p.writeString(id);
        p.writeString(key);
        p.writeString(macAddress);
        p.writeString(model);
        p.writeString(name1);
        p.writeString(name2);
        p.writeString(customName1);
        p.writeString(customName2);
        p.writeString(displayName);
        putBool(p, output1);
        putBool(p, output2);
        p.writeString(relay1);
        p.writeString(relay2);
        putBool(p, output1LatchStatus);
        putBool(p, output2LatchStatus);
        putBool(p, output1Latch);
        putBool(p, output2Latch);
        putBool(p, output1Disabled);
        putBool(p, output2Disabled);
        p.writeString(output1Icon);
        p.writeString(output1Color);
        p.writeString(output2Icon);
        p.writeString(output2Color);
        p.writeInt(gateIndex);
        p.writeString(validUntil);
        putBool(p, notifications);
        p.writeString(callGroupId);
        p.writeInt(callOrder);
        putBool(p, googleAssistantActive);
        p.writeString(output1GoogleAssistantChallenge);
        p.writeString(output2GoogleAssistantChallenge);
        p.writeString(groupId);
        putBool(p, localOnly);
        p.writeString(simStatus);
        p.writeParcelable(geoFence1, flags);
        p.writeParcelable(geoFence2, flags);
        putBool(p, faceRecEnableByUser);
        putBool(p, faceRecEnableByAdmin);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Device> CREATOR = new Creator<Device>() {
        @Override public Device createFromParcel(Parcel in) {
            Device d = new Device();
            d.address = in.readString();
            d.admin = readBool(in);
            d.id = in.readString();
            d.key = in.readString();
            d.macAddress = in.readString();
            d.model = in.readString();
            d.name1 = in.readString();
            d.name2 = in.readString();
            d.customName1 = in.readString();
            d.customName2 = in.readString();
            d.displayName = in.readString();
            d.output1 = readBool(in);
            d.output2 = readBool(in);
            d.relay1 = in.readString();
            d.relay2 = in.readString();
            d.output1LatchStatus = readBool(in);
            d.output2LatchStatus = readBool(in);
            d.output1Latch = readBool(in);
            d.output2Latch = readBool(in);
            d.output1Disabled = readBool(in);
            d.output2Disabled = readBool(in);
            d.output1Icon = in.readString();
            d.output1Color = in.readString();
            d.output2Icon = in.readString();
            d.output2Color = in.readString();
            d.gateIndex = in.readInt();
            d.validUntil = in.readString();
            d.notifications = readBool(in);
            d.callGroupId = in.readString();
            d.callOrder = in.readInt();
            d.googleAssistantActive = readBool(in);
            d.output1GoogleAssistantChallenge = in.readString();
            d.output2GoogleAssistantChallenge = in.readString();
            d.groupId = in.readString();
            d.localOnly = readBool(in);
            d.simStatus = in.readString();
            d.geoFence1 = in.readParcelable(Device.class.getClassLoader());
            d.geoFence2 = in.readParcelable(Device.class.getClassLoader());
            d.faceRecEnableByUser = readBool(in);
            d.faceRecEnableByAdmin = readBool(in);
            return d;
        }

        @Override public Device[] newArray(int size) {
            return new Device[size];
        }

        private Boolean readBool(Parcel in) {
            byte b = in.readByte();
            if (b == 0) return null;
            return b == 1;
        }
    };
}
