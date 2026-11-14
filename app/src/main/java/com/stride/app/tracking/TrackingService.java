
package com.stride.app.tracking;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.*;
import com.stride.app.MainActivity;
import com.stride.app.data.DailyStats;
import com.stride.app.data.RoutePoint;
import com.stride.app.data.StrideDatabase;
import com.stride.app.data.WalkSession;
import com.stride.app.utils.DateUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackingService extends Service
        implements SensorEventListener {

    public static final String ACTION_ENABLE_DAILY =
            "com.stride.app.ENABLE_DAILY";

    public static final String ACTION_START =
            "com.stride.app.START";

    public static final String ACTION_STOP =
            "com.stride.app.STOP";

    public static final String ACTION_AUTO_ON =
            "com.stride.app.AUTO_ON";

    public static final String ACTION_AUTO_OFF =
            "com.stride.app.AUTO_OFF";

    public static final String ACTION_UPDATE =
            "com.stride.app.UPDATE";

    public static final String EXTRA_STEPS = "steps";
    public static final String EXTRA_TODAY_STEPS = "today_steps";
    public static final String EXTRA_DISTANCE = "distance";
    public static final String EXTRA_LATITUDE = "latitude";
    public static final String EXTRA_LONGITUDE = "longitude";
    public static final String EXTRA_HAS_LOCATION = "has_location";

    private static final String CHANNEL = "stride_tracking";
    private static final int NOTIFICATION_ID = 41;

    private FusedLocationProviderClient fused;
    private LocationCallback locationCallback;

    private SensorManager sensorManager;
    private Sensor stepSensor;

    private ExecutorService io;
    private Handler mainHandler;

    private final List<RoutePoint> routePoints =
            new ArrayList<>();

    private boolean tracking = false;
    private boolean dailyMode = false;
    private boolean autoMode = false;
    private boolean dailyStateReady = false;

    private long startedAt;
    private long sessionStepsStart = -1;
    private long lastSensorTotal = -1;

    private int currentSteps = 0;
    private int todaySteps = 0;

    private double distanceMeters = 0;

    private Location lastLocation;

    private String sessionTitle = "Walking session";

    private SharedPreferences stepPrefs;

    @Override
    public void onCreate() {
        super.onCreate();

        io = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        fused = LocationServices.getFusedLocationProviderClient(this);

        sensorManager = (SensorManager)
                getSystemService(SENSOR_SERVICE);

        stepSensor = sensorManager.getDefaultSensor(
                Sensor.TYPE_STEP_COUNTER
        );

        stepPrefs = getSharedPreferences(
                "stride_step_state",
                MODE_PRIVATE
        );

        createChannel();

        // Load saved daily state before registering the sensor.
        io.execute(() -> {

            String today = DateUtils.todayKey();

            DailyStats stats = StrideDatabase.get(
                    getApplicationContext()
            ).strideDao().getDailyStats(today);

            int savedSteps = stepPrefs.getString(
                    "date",
                    ""
            ).equals(today)
                    ? stepPrefs.getInt("daily_steps", 0)
                    : (stats == null ? 0 : stats.steps);

            long savedSensorTotal = stepPrefs.getLong(
                    "last_sensor_total",
                    -1
            );

            mainHandler.post(() -> {

                todaySteps = savedSteps;
                lastSensorTotal = savedSensorTotal;
                dailyStateReady = true;

                if (stepSensor != null) {
                    sensorManager.registerListener(
                            this,
                            stepSensor,
                            SensorManager.SENSOR_DELAY_NORMAL
                    );
                }

                sendUpdate();
            });
        });
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        String action = intent == null
                ? ""
                : intent.getAction();

        if (ACTION_ENABLE_DAILY.equals(action)) {

            dailyMode = true;
            startForegroundServiceNotification(false);
            sendUpdate();

        } else if (ACTION_START.equals(action)) {

            startTracking(false);

        } else if (ACTION_AUTO_ON.equals(action)) {

            autoMode = true;
            dailyMode = true;

            getSharedPreferences(
                    "stride_settings",
                    MODE_PRIVATE
            ).edit()
                    .putBoolean("auto_mode", true)
                    .apply();

            startTracking(true);

        } else if (ACTION_STOP.equals(action)) {

            if (tracking) {
                stopTracking();
            }

            autoMode = false;
            dailyMode = true;

            getSharedPreferences(
                    "stride_settings",
                    MODE_PRIVATE
            ).edit()
                    .putBoolean("auto_mode", false)
                    .apply();

            startForegroundServiceNotification(false);

        } else if (ACTION_AUTO_OFF.equals(action)) {

            if (tracking) {
                stopTracking();
            }

            autoMode = false;
            dailyMode = true;

            getSharedPreferences(
                    "stride_settings",
                    MODE_PRIVATE
            ).edit()
                    .putBoolean("auto_mode", false)
                    .apply();

            startForegroundServiceNotification(false);
        }

        return START_STICKY;
    }

    // --------------------------------------------------
    // FOREGROUND NOTIFICATION
    // --------------------------------------------------

    private void startForegroundServiceNotification(
            boolean includeLocation
    ) {

        Notification notification = buildNotification(
                tracking
                        ? "Recording your walking session"
                        : "Counting your daily steps"
        );

        if (Build.VERSION.SDK_INT >= 34) {

            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;

            if (includeLocation) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }

            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    types
            );

        } else if (Build.VERSION.SDK_INT >= 29) {

            int types = includeLocation
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    : 0;

            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    types
            );

        } else {

            startForeground(
                    NOTIFICATION_ID,
                    notification
            );
        }
    }

    // --------------------------------------------------
    // START WALKING SESSION
    // --------------------------------------------------

    private void startTracking(boolean automatic) {

        if (tracking) {
            return;
        }

        if (!hasLocationPermission()) {
            stopSelf();
            return;
        }

        tracking = true;
        dailyMode = true;
        autoMode = automatic;

        startedAt = System.currentTimeMillis();

        sessionStepsStart = -1;
        currentSteps = 0;
        distanceMeters = 0;

        lastLocation = null;
        routePoints.clear();

        sessionTitle = automatic
                ? "Automatic activity"
                : "Walking session";

        startForegroundServiceNotification(true);

        requestLocationUpdates();
        sendUpdate();
    }

    // --------------------------------------------------
    // LOCATION PERMISSION
    // --------------------------------------------------

    private boolean hasLocationPermission() {

        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    // --------------------------------------------------
    // GPS TRACKING
    // --------------------------------------------------

    private void requestLocationUpdates() {

        if (!hasLocationPermission()) {
            return;
        }

        LocationRequest request =
                new LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        5000
                )
                        .setMinUpdateDistanceMeters(3f)
                        .build();

        locationCallback = new LocationCallback() {

            @Override
            public void onLocationResult(LocationResult result) {

                if (result == null || !tracking) {
                    return;
                }

                for (Location location : result.getLocations()) {

                    if (location == null || !tracking) {
                        continue;
                    }

                    if (lastLocation != null) {

                        float delta =
                                lastLocation.distanceTo(location);

                        if (delta > 1 && delta < 100) {
                            distanceMeters += delta;
                        }
                    }

                    lastLocation = location;

                    routePoints.add(
                            new RoutePoint(
                                    0,
                                    location.getLatitude(),
                                    location.getLongitude(),
                                    System.currentTimeMillis()
                            )
                    );

                    if (routePoints.size() > 5000) {
                        routePoints.remove(0);
                    }

                    sendUpdate();
                }
            }
        };

        try {

            fused.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
            );

        } catch (SecurityException e) {

            tracking = false;
            startForegroundServiceNotification(false);
        }
    }

    // --------------------------------------------------
    // AUTOMATIC DAILY STEP COUNTING
    // --------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent event) {

        if (!dailyStateReady || event.sensor.getType()
                != Sensor.TYPE_STEP_COUNTER) {
            return;
        }

        long sensorTotal = Math.round(event.values[0]);

        String today = DateUtils.todayKey();

        int delta = 0;

        if (lastSensorTotal >= 0) {

            if (sensorTotal >= lastSensorTotal) {

                delta = (int) Math.min(
                        sensorTotal - lastSensorTotal,
                        Integer.MAX_VALUE
                );

            } else {

                // Sensor counter reset, usually after device reboot.
                delta = (int) Math.min(
                        sensorTotal,
                        Integer.MAX_VALUE
                );
            }
        }

        // New day: reset the daily count, but retain
        // the sensor difference since the previous reading.
        String savedDate = stepPrefs.getString(
                "date",
                ""
        );

        if (!today.equals(savedDate)) {
            todaySteps = 0;
        }

        todaySteps += delta;
        lastSensorTotal = sensorTotal;

        stepPrefs.edit()
                .putString("date", today)
                .putInt("daily_steps", todaySteps)
                .putLong("last_sensor_total", lastSensorTotal)
                .apply();

        // Session steps are separate from daily steps.
        if (tracking) {

            if (sessionStepsStart < 0) {
                sessionStepsStart = sensorTotal;
            }

            currentSteps = Math.max(
                    0,
                    (int) Math.round(
                            sensorTotal - sessionStepsStart
                    )
            );
        }

        saveDailySteps();
        sendUpdate();
    }

    private void saveDailySteps() {

        final int steps = todaySteps;
        final String day = DateUtils.todayKey();

        io.execute(() -> {

            StrideDatabase db = StrideDatabase.get(
                    getApplicationContext()
            );

            DailyStats old = db.strideDao().getDailyStats(day);

            double distance = old == null
                    ? 0
                    : old.distanceMeters;

            int minutes = old == null
                    ? 0
                    : old.minutes;

            db.strideDao().upsertDailyStats(
                    new DailyStats(
                            day,
                            steps,
                            distance,
                            minutes
                    )
            );
        });
    }

    @Override
    public void onAccuracyChanged(
            Sensor sensor,
            int accuracy
    ) {
    }

    // --------------------------------------------------
    // STOP WALKING AND SAVE SESSION
    // --------------------------------------------------

    private void stopTracking() {

        if (!tracking) {
            return;
        }

        tracking = false;

        if (locationCallback != null) {

            fused.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }

        long endedAt = System.currentTimeMillis();

        int steps = Math.max(0, currentSteps);

        double distance = distanceMeters;

        long duration = Math.max(
                0,
                endedAt - startedAt
        );

        List<RoutePoint> points =
                new ArrayList<>(routePoints);

        long sessionStart = startedAt;

        String title = sessionTitle;

        routePoints.clear();

        io.execute(() -> {

            StrideDatabase db = StrideDatabase.get(
                    getApplicationContext()
            );

            WalkSession session = new WalkSession(
                    sessionStart,
                    endedAt,
                    distance,
                    duration,
                    steps,
                    title
            );

            long sessionId =
                    db.strideDao().insertSession(session);

            if (!points.isEmpty()) {

                List<RoutePoint> savedPoints =
                        new ArrayList<>();

                for (RoutePoint point : points) {

                    savedPoints.add(
                            new RoutePoint(
                                    sessionId,
                                    point.latitude,
                                    point.longitude,
                                    point.recordedAt
                            )
                    );
                }

                db.strideDao().insertRoutePoints(savedPoints);
            }

            // Daily steps are NOT increased here.
            // The sensor already counted them.
            String day = DateUtils.todayKey();

            DailyStats old =
                    db.strideDao().getDailyStats(day);

            int savedDailySteps = stepPrefs.getInt(
                    "daily_steps",
                    0
            );

            db.strideDao().upsertDailyStats(
                    new DailyStats(
                            day,
                            savedDailySteps,
                            (old == null ? 0 : old.distanceMeters)
                                    + distance,
                            (old == null ? 0 : old.minutes)
                                    + (int) (duration / 60000)
                    )
            );
        });

        currentSteps = 0;
        distanceMeters = 0;
        lastLocation = null;

        sendUpdate();
    }

    // --------------------------------------------------
    // LIVE BROADCAST
    // --------------------------------------------------

    private void sendUpdate() {

        Intent update = new Intent(ACTION_UPDATE);

        update.setPackage(getPackageName());

        update.putExtra(EXTRA_STEPS, currentSteps);
        update.putExtra(EXTRA_TODAY_STEPS, todaySteps);
        update.putExtra(EXTRA_DISTANCE, distanceMeters);

        if (lastLocation != null) {

            update.putExtra(
                    EXTRA_LATITUDE,
                    lastLocation.getLatitude()
            );

            update.putExtra(
                    EXTRA_LONGITUDE,
                    lastLocation.getLongitude()
            );

            update.putExtra(EXTRA_HAS_LOCATION, true);

        } else {

            update.putExtra(EXTRA_HAS_LOCATION, false);
        }

        sendBroadcast(update);

        if (tracking || dailyMode) {

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            if (manager != null) {

                String message = tracking
                        ? currentSteps + " session steps • "
                        + String.format(
                        Locale.US,
                        "%.2f km",
                        distanceMeters / 1000
                )
                        : todaySteps + " steps today";

                manager.notify(
                        NOTIFICATION_ID,
                        buildNotification(message)
                );
            }
        }
    }

    // --------------------------------------------------
    // NOTIFICATION
    // --------------------------------------------------

    private Notification buildNotification(String message) {

        Intent open = new Intent(this, MainActivity.class);

        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("STRIDE")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pending)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL,
                            "STRIDE daily step tracking",
                            NotificationManager.IMPORTANCE_LOW
                    );

            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }
    }

    // --------------------------------------------------
    // SERVICE
    // --------------------------------------------------

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {

        tracking = false;

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        if (locationCallback != null && fused != null) {
            fused.removeLocationUpdates(locationCallback);
        }

        if (io != null) {
            io.shutdown();
        }

        super.onDestroy();
    }
}
