package com.example.geolink.model;

public class UserLocation {
    public String uid;
    public String name;
    public double latitude;
    public double longitude;
    public boolean sharingEnabled;
    public long updatedAt;

    public UserLocation() {
    }

    public UserLocation(String uid, String name, double latitude, double longitude, boolean sharingEnabled, long updatedAt) {
        this.uid = uid;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.sharingEnabled = sharingEnabled;
        this.updatedAt = updatedAt;
    }
}
