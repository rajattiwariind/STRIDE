package com.stride.app.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {WalkSession.class, RoutePoint.class, DailyStats.class}, version = 1, exportSchema = false)
public abstract class StrideDatabase extends RoomDatabase {
    private static volatile StrideDatabase INSTANCE;
    public abstract StrideDao strideDao();

    public static StrideDatabase get(Context context) {
        if (INSTANCE == null) {
            synchronized (StrideDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            StrideDatabase.class, "stride_local.db").build();
                }
            }
        }
        return INSTANCE;
    }
}
