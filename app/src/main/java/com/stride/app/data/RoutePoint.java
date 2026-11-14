package com.stride.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "route_points")
public class RoutePoint {
    @PrimaryKey(autoGenerate = true) public long id;
    public long sessionId;
    public double latitude;
    public double longitude;
    public long recordedAt;

    public RoutePoint(long sessionId, double latitude, double longitude, long recordedAt) {
        this.sessionId = sessionId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.recordedAt = recordedAt;
    }
}
