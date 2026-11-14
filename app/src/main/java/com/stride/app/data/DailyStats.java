package com.stride.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "daily_stats")
public class DailyStats {

    @PrimaryKey
    @NonNull
    public String dateKey = "";

    public int steps;
    public double distanceMeters;
    public int minutes;

    public DailyStats(@NonNull String dateKey, int steps,
                      double distanceMeters, int minutes) {
        this.dateKey = dateKey;
        this.steps = steps;
        this.distanceMeters = distanceMeters;
        this.minutes = minutes;
    }
}