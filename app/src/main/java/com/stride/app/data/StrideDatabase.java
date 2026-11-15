package com.stride.app.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                WalkSession.class,
                RoutePoint.class,
                DailyStats.class,
                StepMinute.class
        },
        version = 2,
        exportSchema = false
)
public abstract class StrideDatabase extends RoomDatabase {

    private static volatile StrideDatabase INSTANCE;

    public abstract StrideDao strideDao();

    // Preserve existing version-1 data and create only the new table.
    private static final Migration MIGRATION_1_2 =
            new Migration(1, 2) {
                @Override
                public void migrate(
                        @NonNull SupportSQLiteDatabase database
                ) {

                    database.execSQL(
                            "CREATE TABLE IF NOT EXISTS `step_minutes` (" +
                                    "`dateKey` TEXT NOT NULL, " +
                                    "`minuteStartMillis` INTEGER NOT NULL, " +
                                    "`steps` INTEGER NOT NULL, " +
                                    "PRIMARY KEY(`dateKey`, `minuteStartMillis`))"
                    );

                    database.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_step_minutes_dateKey` " +
                                    "ON `step_minutes` (`dateKey`)"
                    );
                }
            };

    public static StrideDatabase get(Context context) {

        if (INSTANCE == null) {

            synchronized (StrideDatabase.class) {

                if (INSTANCE == null) {

                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    StrideDatabase.class,
                                    "stride_local.db"
                            )
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }

        return INSTANCE;
    }
}