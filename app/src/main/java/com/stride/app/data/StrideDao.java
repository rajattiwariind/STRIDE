package com.stride.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface StrideDao {

    @Insert
    long insertSession(WalkSession session);

    @Insert
    void insertRoutePoints(List<RoutePoint> points);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertDailyStats(DailyStats stats);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertStepMinute(StepMinute stepMinute);

    @Query("SELECT * FROM walk_sessions ORDER BY startedAt DESC")
    List<WalkSession> getSessions();

    @Query("SELECT * FROM route_points WHERE sessionId = :sessionId ORDER BY recordedAt ASC")
    List<RoutePoint> getRoutePoints(long sessionId);

    @Query("SELECT * FROM daily_stats ORDER BY dateKey DESC LIMIT 7")
    List<DailyStats> getLastSevenDays();

    @Query("SELECT COALESCE(SUM(steps), 0) FROM daily_stats WHERE dateKey = :dateKey")
    int getStepsForDay(String dateKey);

    @Query("SELECT * FROM daily_stats WHERE dateKey = :dateKey LIMIT 1")
    DailyStats getDailyStats(String dateKey);

    @Query("SELECT * FROM step_minutes WHERE dateKey = :dateKey AND minuteStartMillis = :minuteStartMillis LIMIT 1")
    StepMinute getStepMinute(
            String dateKey,
            long minuteStartMillis
    );

    @Query("SELECT * FROM step_minutes WHERE dateKey = :dateKey ORDER BY minuteStartMillis ASC")
    List<StepMinute> getStepMinutesForDay(String dateKey);
}