package com.stride.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "walk_sessions")
public class WalkSession {
    @PrimaryKey(autoGenerate = true) public long id;
    public long startedAt;
    public long endedAt;
    public double distanceMeters;
    public long durationMillis;
    public int steps;
    public String title;

    public WalkSession(long startedAt, long endedAt, double distanceMeters,
                       long durationMillis, int steps, String title) {
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.distanceMeters = distanceMeters;
        this.durationMillis = durationMillis;
        this.steps = steps;
        this.title = title;
    }
}
