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
import com.stride.app.data.StepMinute;
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
    private long sessionStepsStart = 0;

    private int currentSteps = 0;
    private int todaySteps = 0;

    // --------------------------------------------------
    // STEP DETECTION FILTER
    // --------------------------------------------------

    private static final float STEP_PEAK_THRESHOLD = 1.35f;

    private static final long MIN_STEP_INTERVAL_MS = 300;
    private static final long MAX_STEP_INTERVAL_MS = 1400;

    private static final long MAX_CADENCE_VARIATION_MS = 450;

    private float gravityMagnitude = 9.81f;

    private float previousAbsLinear = 0f;
    private float olderAbsLinear = 0f;

    private long previousSampleTime = 0L;
    private long olderSampleTime = 0L;

    private long lastCandidatePeakTime = 0L;
    private long previousCandidateInterval = 0L;

    private int cadenceCandidateCount = 0;

    private double distanceMeters = 0;

    private Location lastLocation;

    private String sessionTitle = "Walking session";

    private SharedPreferences stepPrefs;

    // --------------------------------------------------
    // ON CREATE
    // --------------------------------------------------

    @Override
    public void onCreate() {

        super.onCreate();

        io = Executors.newSingleThreadExecutor();

        mainHandler = new Handler(Looper.getMainLooper());

        fused = LocationServices.getFusedLocationProviderClient(this);

        sensorManager = (SensorManager)
                getSystemService(SENSOR_SERVICE);

        // Use accelerometer-based detection with cadence filtering.
        stepSensor = sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER
        );

        stepPrefs = getSharedPreferences(
                "stride_step_state",
                MODE_PRIVATE
        );

        createChannel();

        // Load saved daily steps before registering the sensor.
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

            mainHandler.post(() -> {

                todaySteps = savedSteps;

                dailyStateReady = true;

                if (stepSensor != null) {

                    sensorManager.registerListener(
                            this,
                            stepSensor,
                            SensorManager.SENSOR_DELAY_UI
                    );
                }

                sendUpdate();
            });
        });
    }

    // --------------------------------------------------
    // START COMMAND
    // --------------------------------------------------

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        String action = intent == null
                ? ""
                : intent.getAction();

        // START_STICKY can restart the service with a null Intent.
        // Restore the daily-only mode instead of leaving it without a foreground notification.
        if (action == null || action.isEmpty()) {
            dailyMode = true;
            startForegroundServiceNotification(false);
            sendUpdate();
        } else if (ACTION_ENABLE_DAILY.equals(action)) {

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

        sessionStepsStart = todaySteps;

        currentSteps = 0;
        distanceMeters = 0;

        cadenceCandidateCount = 0;
        lastCandidatePeakTime = 0L;
        previousCandidateInterval = 0L;

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

        if (!dailyStateReady
                || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        long sampleTime = event.timestamp / 1_000_000L;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        float magnitude = (float) Math.sqrt(
                x * x + y * y + z * z
        );

        // Smooth gravity and subtract it from the measured magnitude.
        gravityMagnitude =
                0.90f * gravityMagnitude + 0.10f * magnitude;

        float linearAcceleration = magnitude - gravityMagnitude;

        float absoluteLinear = Math.abs(linearAcceleration);

        // Identify a local peak using three consecutive samples.
        if (previousSampleTime > 0L
                && olderSampleTime > 0L
                && previousAbsLinear > olderAbsLinear
                && previousAbsLinear >= absoluteLinear
                && previousAbsLinear >= STEP_PEAK_THRESHOLD) {

            processStepCandidate(previousSampleTime);
        }

        olderAbsLinear = previousAbsLinear;
        olderSampleTime = previousSampleTime;

        previousAbsLinear = absoluteLinear;
        previousSampleTime = sampleTime;
    }

    /**
     * Require three peaks with a plausible and reasonably stable cadence
     * before counting steps. Once walking is confirmed, count each next
     * regular peak.
     */
    private void processStepCandidate(long peakTime) {

        if (lastCandidatePeakTime == 0L) {

            lastCandidatePeakTime = peakTime;

            cadenceCandidateCount = 1;

            previousCandidateInterval = 0L;

            return;
        }

        long interval = peakTime - lastCandidatePeakTime;

        if (interval < MIN_STEP_INTERVAL_MS
                || interval > MAX_STEP_INTERVAL_MS) {

            lastCandidatePeakTime = peakTime;

            cadenceCandidateCount = 1;

            previousCandidateInterval = 0L;

            return;
        }

        if (previousCandidateInterval > 0L
                && Math.abs(interval - previousCandidateInterval)
                > MAX_CADENCE_VARIATION_MS) {

            lastCandidatePeakTime = peakTime;

            cadenceCandidateCount = 1;

            previousCandidateInterval = 0L;

            return;
        }

        lastCandidatePeakTime = peakTime;

        previousCandidateInterval = interval;

        cadenceCandidateCount++;

        if (cadenceCandidateCount == 3) {

            addConfirmedSteps(3);

        } else if (cadenceCandidateCount > 3) {

            addConfirmedSteps(1);
        }
    }

    // --------------------------------------------------
    // ADD CONFIRMED STEPS
    // --------------------------------------------------

    private void addConfirmedSteps(int amount) {

        if (amount <= 0) {
            return;
        }

        String today = DateUtils.todayKey();

        String savedDate = stepPrefs.getString(
                "date",
                ""
        );

        if (!today.equals(savedDate)) {

            todaySteps = 0;

            cadenceCandidateCount = 0;

            lastCandidatePeakTime = 0L;

            previousCandidateInterval = 0L;
        }

        todaySteps += amount;

        stepPrefs.edit()
                .putString("date", today)
                .putInt("daily_steps", todaySteps)
                .apply();

        if (tracking) {

            currentSteps = Math.max(
                    0,
                    todaySteps - (int) sessionStepsStart
            );
        }

        // Save the confirmed steps into the current minute bucket.
        // This powers the steps-per-minute graph in Insights.
        final int stepsToSave = amount;
        final String minuteDate = today;
        final long minuteStart =
                (System.currentTimeMillis() / 60000L) * 60000L;

        io.execute(() -> {

            StrideDatabase db = StrideDatabase.get(
                    getApplicationContext()
            );

            StepMinute existing = db.strideDao().getStepMinute(
                    minuteDate,
                    minuteStart
            );

            int minuteSteps = existing == null
                    ? stepsToSave
                    : existing.steps + stepsToSave;

            db.strideDao().upsertStepMinute(
                    new StepMinute(
                            minuteDate,
                            minuteStart,
                            minuteSteps
                    )
            );
        });

        saveDailySteps();

        sendUpdate();
    }

    // --------------------------------------------------
    // SAVE DAILY STEPS
    // --------------------------------------------------

    private double getEstimatedStepLengthMeters() {

        SharedPreferences profile = getSharedPreferences(
                "stride_settings",
                MODE_PRIVATE
        );

        int heightCm = profile.getInt("height_cm", 0);

        // 0.414 x height is a common walking step-length estimate.
        // Fall back to 0.72 m when height is not provided.
        return heightCm > 0
                ? (heightCm / 100.0) * 0.414
                : 0.72;
    }

    private double getEstimatedDistanceMeters(int steps) {
        return Math.max(0, steps) * getEstimatedStepLengthMeters();
    }

    private double getEstimatedCalories(int steps) {

        SharedPreferences profile = getSharedPreferences(
                "stride_settings",
                MODE_PRIVATE
        );

        float weightKg = profile.getFloat("weight_kg", 0f);

        if (weightKg <= 0f) {
            return 0;
        }

        double distanceKm = getEstimatedDistanceMeters(steps) / 1000.0;

        // Approximation only: weight x kilometres x 0.5.
        return weightKg * distanceKm * 0.5;
    }

    private void saveDailySteps() {

        final int steps = todaySteps;
        final String day = DateUtils.todayKey();
        final double estimatedDistance = getEstimatedDistanceMeters(steps);
        final double estimatedCalories = getEstimatedCalories(steps);

        // Persist the daily estimates separately as well, keyed by date.
        // Calories remain estimates and are only meaningful when weight is set.
        getSharedPreferences(
                "stride_daily_metrics",
                MODE_PRIVATE
        ).edit()
                .putString("date", day)
                .putFloat("distance_meters_" + day, (float) estimatedDistance)
                .putFloat("calories_kcal_" + day, (float) estimatedCalories)
                .apply();

        io.execute(() -> {

            StrideDatabase db = StrideDatabase.get(
                    getApplicationContext()
            );

            DailyStats old = db.strideDao().getDailyStats(day);

            // Count active minutes from the minute buckets written by the
            // accelerometer step detector. No GPS session is required.
            List<StepMinute> minuteRows =
                    db.strideDao().getStepMinutesForDay(day);

            int activeMinutes = 0;

            for (StepMinute minute : minuteRows) {
                if (minute.steps > 0) {
                    activeMinutes++;
                }
            }

            // DailyStats stores steps, estimated distance and active minutes.
            db.strideDao().upsertDailyStats(
                    new DailyStats(
                            day,
                            steps,
                            estimatedDistance,
                            activeMinutes
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

        update.putExtra(
                EXTRA_DISTANCE,
                getEstimatedDistanceMeters(todaySteps)
        );

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