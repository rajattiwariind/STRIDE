package com.stride.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

@Entity(
        tableName = "step_minutes",
        primaryKeys = {"dateKey", "minuteStartMillis"},
        indices = {@Index(value = {"dateKey"})}
)
public class StepMinute {

    @NonNull
    public String dateKey;

    public long minuteStartMillis;

    public int steps;

    public StepMinute(
            @NonNull String dateKey,
            long minuteStartMillis,
            int steps
    ) {
        this.dateKey = dateKey;
        this.minuteStartMillis = minuteStartMillis;
        this.steps = steps;
    }
}