
package com.stride.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import com.stride.app.data.StrideDatabase;
import com.stride.app.data.WalkSession;
import com.stride.app.tracking.TrackingService;
import com.stride.app.utils.DateUtils;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int DAILY_GOAL = 10000;
    private static final int MILESTONE_GOAL = 5000;

    private static final int[] MILESTONES = {
            1000,
            2500,
            5000,
            7500,
            10000
    };

    private LinearLayout content;

    private TextView previousStepValue;
    private TextView currentWalkValue;
    private TextView remainingStepValue;
    private TextView distanceValue;

    private MilestoneOrbitView milestoneOrbit;

    private MaterialButton walkButton;

    private boolean tracking = false;
    private boolean receiverRegistered = false;

    private boolean milestoneStateInitialized = false;
    private String milestoneDateKey = "";

    private int liveSteps = 0;
    private int todaySteps = 0;

    private double liveDistance = 0;

    private final ExecutorService io =
            Executors.newSingleThreadExecutor();

    // --------------------------------------------------
    // ACTIVITY RECOGNITION PERMISSION
    // --------------------------------------------------

    private final ActivityResultLauncher<String>
            activityPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {

                        if (granted) {
                            startDailyStepService();
                        } else {
                            toast(
                                    "Allow physical activity permission to count daily steps."
                            );
                        }
                    }
            );

    // --------------------------------------------------
    // WALKING PERMISSIONS
    // --------------------------------------------------

    private final ActivityResultLauncher<String[]> permissions =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {

                        boolean location =
                                Boolean.TRUE.equals(
                                        result.get(Manifest.permission.ACCESS_FINE_LOCATION)
                                ) ||
                                        Boolean.TRUE.equals(
                                                result.get(Manifest.permission.ACCESS_COARSE_LOCATION)
                                        );

                        if (location) {
                            startManualTracking();
                        } else {
                            toast(
                                    "Location permission is needed to record your GPS route."
                            );
                        }

                        if (hasActivityPermission()) {
                            startDailyStepService();
                        }
                    }
            );

    // --------------------------------------------------
    // TRACKING BROADCAST RECEIVER
    // --------------------------------------------------

    private final BroadcastReceiver updates =
            new BroadcastReceiver() {

                @Override
                public void onReceive(
                        Context context,
                        Intent intent
                ) {

                    if (intent == null) {
                        return;
                    }

                    liveSteps = intent.getIntExtra(
                            TrackingService.EXTRA_STEPS,
                            liveSteps
                    );

                    todaySteps = intent.getIntExtra(
                            TrackingService.EXTRA_TODAY_STEPS,
                            todaySteps
                    );

                    liveDistance = intent.getDoubleExtra(
                            TrackingService.EXTRA_DISTANCE,
                            liveDistance
                    );

                    updateDashboardValues();
                }
            };

    // --------------------------------------------------
    // ON CREATE
    // --------------------------------------------------

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(0xFF08090D);
        getWindow().setNavigationBarColor(0xFF08090D);

        buildShell();
        showHome();

        IntentFilter filter =
                new IntentFilter(TrackingService.ACTION_UPDATE);

        if (Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                    updates,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );

        } else {

            registerReceiver(updates, filter);
        }

        receiverRegistered = true;

        loadTodayStats();

        requestDailyStepPermission();
    }

    // --------------------------------------------------
    // DAILY STEP PERMISSION
    // --------------------------------------------------

    private boolean hasActivityPermission() {

        if (Build.VERSION.SDK_INT < 29) {
            return true;
        }

        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestDailyStepPermission() {

        if (Build.VERSION.SDK_INT >= 29 &&
                !hasActivityPermission()) {

            activityPermissionLauncher.launch(
                    Manifest.permission.ACTIVITY_RECOGNITION
            );

        } else {

            startDailyStepService();
        }
    }

    // --------------------------------------------------
    // START AUTOMATIC DAILY STEP SERVICE
    // --------------------------------------------------

    private void startDailyStepService() {

        if (!hasActivityPermission()) {
            return;
        }

        Intent service = new Intent(
                this,
                TrackingService.class
        );

        service.setAction(
                TrackingService.ACTION_ENABLE_DAILY
        );

        try {

            ContextCompat.startForegroundService(
                    this,
                    service
            );

        } catch (Exception e) {

            toast(
                    "Unable to start daily step tracking. Please reopen STRIDE."
            );
        }
    }

    // --------------------------------------------------
    // MAIN APPLICATION LAYOUT
    // --------------------------------------------------

    private void buildShell() {

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF08090D);

        content = new LinearLayout(this);

        content.setOrientation(LinearLayout.VERTICAL);

        content.setPadding(
                dp(20),
                dp(12),
                dp(20),
                dp(10)
        );

        ScrollView scroll = new ScrollView(this);

        scroll.addView(content);

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1
                )
        );

        BottomNavigationView nav =
                new BottomNavigationView(this);

        nav.getMenu().add(0, 1, 0, "Home")
                .setIcon(android.R.drawable.ic_menu_view);

        nav.getMenu().add(0, 2, 1, "Explore")
                .setIcon(android.R.drawable.ic_menu_mapmode);

        nav.getMenu().add(0, 3, 2, "History")
                .setIcon(android.R.drawable.ic_menu_recent_history);

        nav.getMenu().add(0, 4, 3, "Insights")
                .setIcon(android.R.drawable.ic_menu_sort_by_size);

        nav.getMenu().add(0, 5, 4, "Profile")
                .setIcon(android.R.drawable.ic_menu_myplaces);

        nav.setBackgroundColor(0xFF10111A);

        nav.setItemIconTintList(
                android.content.res.ColorStateList.valueOf(
                        0xFF9875FF
                )
        );

        nav.setItemTextColor(
                android.content.res.ColorStateList.valueOf(
                        0xFFB5B5C5
                )
        );

        nav.setLabelVisibilityMode(
                BottomNavigationView.LABEL_VISIBILITY_LABELED
        );

        nav.setOnItemSelectedListener(item -> {

            if (item.getItemId() == 1) {

                showHome();

            } else if (item.getItemId() == 2) {

                startActivity(
                        new Intent(this, MapActivity.class)
                );

            } else if (item.getItemId() == 3) {

                showHistory();

            } else if (item.getItemId() == 4) {

                showInsights();

            } else {

                showProfile();
            }

            return true;
        });

        root.addView(
                nav,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(68)
                )
        );

        setContentView(root);
    }

    // --------------------------------------------------
    // HOME DASHBOARD
    // --------------------------------------------------

    private void showHome() {

        content.removeAllViews();

        header();

        text(
                "Good to see you",
                15,
                0xFF9699AA,
                false,
                26
        );

        text(
                "Ready to move?",
                29,
                0xFFFFFFFF,
                true,
                3
        );

        MaterialCardView hero = card();

        LinearLayout box = column();

        box.setPadding(
                dp(20),
                dp(20),
                dp(20),
                dp(20)
        );

        rowLabel(
                box,
                "TODAY'S ACTIVITY",
                "● AUTO TRACKING"
        );

        milestoneOrbit = new MilestoneOrbitView(this);

        LinearLayout.LayoutParams orbitParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(245)
                );

        orbitParams.topMargin = dp(12);

        box.addView(
                milestoneOrbit,
                orbitParams
        );

        milestoneOrbit.setSteps(todaySteps);

        textIn(
                box,
                "VIOLET: 5K MILESTONE     BLUE: 10K GOAL",
                10,
                0xFFB7A0FF,
                true,
                8
        );

        previousStepValue = textIn(
                box,
                "Previous steps: " +
                        formatSteps(getPreviousSteps()),
                13,
                0xFFB5B5C5,
                false,
                16
        );

        currentWalkValue = textIn(
                box,
                "Current walk: " +
                        formatSteps(liveSteps),
                13,
                0xFFB5B5C5,
                false,
                8
        );

        remainingStepValue = textIn(
                box,
                "Remaining: " +
                        formatSteps(getRemainingSteps()),
                15,
                0xFF64D9AE,
                true,
                12
        );

        hero.addView(box);

        content.addView(hero, matchWrap());

        text(
                "Your statistics",
                19,
                0xFFFFFFFF,
                true,
                25
        );

        LinearLayout stats = new LinearLayout(this);

        stats.setOrientation(LinearLayout.HORIZONTAL);

        MaterialCardView dist = statCard(
                "DISTANCE",
                String.format(
                        Locale.getDefault(),
                        "%.2f km",
                        liveDistance / 1000
                ),
                "Current GPS walk"
        );

        MaterialCardView cal = statCard(
                "CALORIES",
                "— kcal",
                "Estimate requires profile"
        );

        stats.addView(
                dist,
                new LinearLayout.LayoutParams(
                        0,
                        dp(116),
                        1
                )
        );

        LinearLayout.LayoutParams cp =
                new LinearLayout.LayoutParams(
                        0,
                        dp(116),
                        1
                );

        cp.leftMargin = dp(10);

        stats.addView(cal, cp);

        content.addView(stats);

        text(
                "Your next journey",
                19,
                0xFFFFFFFF,
                true,
                25
        );

        MaterialCardView journey = card();

        LinearLayout j = column();

        j.setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18)
        );

        textIn(
                j,
                "EXPLORE YOUR SURROUNDINGS",
                10,
                0xFFB7A0FF,
                true,
                10
        );

        textIn(
                j,
                "Every step tells a story.",
                20,
                0xFFFFFFFF,
                true,
                7
        );

        textIn(
                j,
                "Record your route and see where your walk takes you.",
                13,
                0xFF9699AA,
                false,
                16
        );

        walkButton = new MaterialButton(this);

        walkButton.setText(
                tracking
                        ? "STOP WALKING"
                        : "START WALKING  →"
        );

        walkButton.setTextColor(0xFFFFFFFF);

        walkButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        tracking
                                ? 0xFFB64D69
                                : 0xFF7955D9
                )
        );

        walkButton.setCornerRadius(dp(15));

        walkButton.setOnClickListener(v -> {

            if (tracking) {

                stopTracking();

            } else {

                requestTrackingPermissions();
            }
        });

        j.addView(
                walkButton,
                new LinearLayout.LayoutParams(
                        -1,
                        dp(54)
                )
        );

        journey.addView(j);

        content.addView(journey, matchWrap());

        text(
                "Weekly activity",
                19,
                0xFFFFFFFF,
                true,
                25
        );

        MaterialCardView week = card();

        LinearLayout w = column();

        w.setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18)
        );

        textIn(
                w,
                "YOUR MOVEMENT",
                10,
                0xFF9699AA,
                true,
                10
        );

        textIn(
                w,
                "Your activity is saved on this device.",
                14,
                0xFFFFFFFF,
                false,
                0
        );

        week.addView(w);

        content.addView(week, matchWrap());

        updateDashboardValues();

        loadTodayStats();
    }

    // --------------------------------------------------
    // DAILY STEP CALCULATIONS
    // --------------------------------------------------

    private int getPreviousSteps() {

        return Math.max(
                0,
                todaySteps - liveSteps
        );
    }

    private int getRemainingSteps() {

        return Math.max(
                0,
                DAILY_GOAL - todaySteps
        );
    }

    private String formatSteps(int steps) {

        return String.format(
                Locale.getDefault(),
                "%,d",
                Math.max(0, steps)
        );
    }

    // --------------------------------------------------
    // UPDATE DASHBOARD
    // --------------------------------------------------

    private void updateDashboardValues() {

        if (milestoneOrbit != null) {
            milestoneOrbit.setSteps(todaySteps);
        }

        if (previousStepValue != null) {

            previousStepValue.setText(
                    "Previous steps: " +
                            formatSteps(getPreviousSteps())
            );
        }

        if (currentWalkValue != null) {

            currentWalkValue.setText(
                    "Current walk: " +
                            formatSteps(liveSteps)
            );
        }

        if (remainingStepValue != null) {

            remainingStepValue.setText(
                    "Remaining: " +
                            formatSteps(getRemainingSteps())
            );
        }

        if (distanceValue != null) {

            distanceValue.setText(
                    String.format(
                            Locale.getDefault(),
                            "%.2f km",
                            liveDistance / 1000
                    )
            );
        }

        checkMilestoneUnlocks();
    }

    // --------------------------------------------------
    // INITIALIZE DAILY MILESTONE STATE
    // --------------------------------------------------

    private void initializeMilestoneState() {

        String today = DateUtils.todayKey();

        if (today.equals(milestoneDateKey)
                && milestoneStateInitialized) {
            return;
        }

        milestoneDateKey = today;
        milestoneStateInitialized = true;

        SharedPreferences prefs =
                getSharedPreferences(
                        "stride_milestones",
                        MODE_PRIVATE
                );

        SharedPreferences.Editor editor = prefs.edit();

        // Existing milestones are marked as already achieved.
        // This prevents old celebrations appearing on app launch.

        for (int milestone : MILESTONES) {

            if (todaySteps >= milestone) {

                editor.putBoolean(
                        today + "_" + milestone,
                        true
                );
            }
        }

        editor.apply();
    }

    // --------------------------------------------------
    // CHECK MILESTONE UNLOCKS
    // --------------------------------------------------

    private void checkMilestoneUnlocks() {

        if (!milestoneStateInitialized) {
            return;
        }

        String today = DateUtils.todayKey();

        // Handle a new day.

        if (!today.equals(milestoneDateKey)) {

            initializeMilestoneState();
            return;
        }

        SharedPreferences prefs =
                getSharedPreferences(
                        "stride_milestones",
                        MODE_PRIVATE
                );

        SharedPreferences.Editor editor = prefs.edit();

        int newlyUnlocked = 0;

        for (int milestone : MILESTONES) {

            String key = today + "_" + milestone;

            if (todaySteps >= milestone &&
                    !prefs.getBoolean(key, false)) {

                editor.putBoolean(key, true);

                // If several milestones are crossed together,
                // celebrate only the highest one.

                newlyUnlocked = milestone;
            }
        }

        editor.apply();

        if (newlyUnlocked > 0) {

            showMilestoneCelebration(newlyUnlocked);
        }
    }

    // --------------------------------------------------
    // MILESTONE TITLES
    // --------------------------------------------------

    private String getMilestoneTitle(int steps) {

        switch (steps) {

            case 1000:
                return "FIRST STEPS";

            case 2500:
                return "MOMENTUM";

            case 5000:
                return "HALFWAY HERO";

            case 7500:
                return "ALMOST THERE";

            case 10000:
                return "DAILY CHAMPION";

            default:
                return "MILESTONE UNLOCKED";
        }
    }

    // --------------------------------------------------
    // MILESTONE MESSAGES
    // --------------------------------------------------

    private String getMilestoneMessage(int steps) {

        switch (steps) {

            case 1000:
                return "Your journey has begun. Every great achievement starts with one step.";

            case 2500:
                return "You're building momentum. Keep moving and make every step count.";

            case 5000:
                return "Halfway there! You've completed 50% of your daily goal.";

            case 7500:
                return "You're almost there. Just 2,500 more steps to become today's champion.";

            case 10000:
                return "You did it! You've completed your 10,000-step daily goal. Outstanding work.";

            default:
                return "Keep moving beyond your limits.";
        }
    }

    // --------------------------------------------------
    // MILESTONE COLORS
    // --------------------------------------------------

    private int getMilestoneColor(int steps) {

        switch (steps) {

            case 1000:
                return 0xFF38BDF8;

            case 2500:
                return 0xFF9875FF;

            case 5000:
                return 0xFFA78BFA;

            case 7500:
                return 0xFFFFB84D;

            case 10000:
                return 0xFF64D9AE;

            default:
                return 0xFF9875FF;
        }
    }

    // --------------------------------------------------
    // MILESTONE CELEBRATION POPUP
    // --------------------------------------------------

    private void showMilestoneCelebration(int steps) {

        if (isFinishing() || isDestroyed()) {
            return;
        }

        Dialog dialog = new Dialog(this);

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);

        root.setGravity(Gravity.CENTER_HORIZONTAL);

        root.setPadding(
                dp(24),
                dp(28),
                dp(24),
                dp(24)
        );

        int accent = getMilestoneColor(steps);

        root.setBackground(
                round(
                        0xFF151722,
                        0xFF35364B,
                        24
                )
        );

        // Achievement symbol

        TextView symbol = new TextView(this);

        symbol.setText("✦");
        symbol.setTextSize(35);
        symbol.setTextColor(0xFFFFFFFF);
        symbol.setGravity(Gravity.CENTER);

        symbol.setBackground(
                round(
                        accent,
                        accent,
                        50
                )
        );

        LinearLayout.LayoutParams symbolParams =
                new LinearLayout.LayoutParams(
                        dp(72),
                        dp(72)
                );

        root.addView(symbol, symbolParams);

        // Heading

        TextView heading = textIn(
                root,
                "MILESTONE UNLOCKED",
                11,
                accent,
                true,
                20
        );

        heading.setGravity(Gravity.CENTER);

        // Achievement title

        TextView title = textIn(
                root,
                getMilestoneTitle(steps),
                24,
                0xFFFFFFFF,
                true,
                12
        );

        title.setGravity(Gravity.CENTER);

        // Step count

        TextView count = textIn(
                root,
                formatSteps(steps) + " STEPS",
                15,
                accent,
                true,
                10
        );

        count.setGravity(Gravity.CENTER);

        // Description

        TextView message = textIn(
                root,
                getMilestoneMessage(steps),
                14,
                0xFFB5B5C5,
                false,
                16
        );

        message.setGravity(Gravity.CENTER);

        // Continue button

        MaterialButton button = new MaterialButton(this);

        button.setText("KEEP MOVING");

        button.setTextColor(0xFFFFFFFF);

        button.setCornerRadius(dp(14));

        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        accent
                )
        );

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(52)
                );

        buttonParams.topMargin = dp(24);

        root.addView(button, buttonParams);

        button.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(root);

        dialog.setCancelable(true);

        dialog.show();

        Window window = dialog.getWindow();

        if (window != null) {

            window.setBackgroundDrawableResource(
                    android.R.color.transparent
            );

            window.setLayout(
                    getResources().getDisplayMetrics().widthPixels
                            - dp(48),
                    WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        // Entrance animation

        root.setScaleX(0.80f);
        root.setScaleY(0.80f);
        root.setAlpha(0f);

        root.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(450)
                .setInterpolator(
                        new OvershootInterpolator(1.1f)
                )
                .start();
    }

    // --------------------------------------------------
    // MILESTONE ORBIT VIEW
    // --------------------------------------------------

    private class MilestoneOrbitView extends View {

        private final Paint paint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF outerRect = new RectF();
        private final RectF innerRect = new RectF();

        private int steps = 0;

        private float animatedSteps = 0;

        private final int BLUE = 0xFF38BDF8;
        private final int VIOLET = 0xFFA78BFA;
        private final int TRACK = 0xFF303449;
        private final int WHITE = 0xFFFFFFFF;
        private final int MUTED = 0xFF9699AA;

        private ValueAnimator activeAnimator;

        public MilestoneOrbitView(Context context) {
            super(context);
        }

        public void setSteps(int newSteps) {

            newSteps = Math.max(0, newSteps);

            if (steps == newSteps) {
                return;
            }

            steps = newSteps;

            if (activeAnimator != null) {
                activeAnimator.cancel();
            }

            activeAnimator = ValueAnimator.ofFloat(
                    animatedSteps,
                    newSteps
            );

            activeAnimator.setDuration(850);

            activeAnimator.setInterpolator(
                    new DecelerateInterpolator()
            );

            activeAnimator.addUpdateListener(animation -> {

                animatedSteps =
                        (float) animation.getAnimatedValue();

                invalidate();
            });

            activeAnimator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {

            super.onDraw(canvas);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            float outerRadius = Math.min(
                    getWidth() * 0.36f,
                    getHeight() * 0.39f
            );

            float innerRadius = outerRadius - dp(20);

            float stroke = dp(10);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);

            // OUTER ORBIT TRACK

            outerRect.set(
                    cx - outerRadius,
                    cy - outerRadius,
                    cx + outerRadius,
                    cy + outerRadius
            );

            paint.setColor(TRACK);

            canvas.drawArc(
                    outerRect,
                    -90,
                    360,
                    false,
                    paint
            );

            // OUTER BLUE DAILY GOAL

            float dailyProgress = Math.min(
                    1f,
                    animatedSteps / DAILY_GOAL
            );

            paint.setColor(BLUE);

            canvas.drawArc(
                    outerRect,
                    -90,
                    360 * dailyProgress,
                    false,
                    paint
            );

            // INNER ORBIT TRACK

            innerRect.set(
                    cx - innerRadius,
                    cy - innerRadius,
                    cx + innerRadius,
                    cy + innerRadius
            );

            paint.setColor(TRACK);

            canvas.drawArc(
                    innerRect,
                    -90,
                    360,
                    false,
                    paint
            );

            // INNER VIOLET MILESTONE

            float milestoneProgress = Math.min(
                    1f,
                    animatedSteps / MILESTONE_GOAL
            );

            paint.setColor(VIOLET);

            canvas.drawArc(
                    innerRect,
                    -90,
                    360 * milestoneProgress,
                    false,
                    paint
            );

            // CENTER TEXT

            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);

            paint.setColor(MUTED);
            paint.setTextSize(dp(11));
            paint.setTypeface(Typeface.DEFAULT);

            canvas.drawText(
                    "TODAY'S STEPS",
                    cx,
                    cy - dp(15),
                    paint
            );

            paint.setColor(WHITE);
            paint.setTextSize(dp(27));

            paint.setTypeface(
                    Typeface.create(
                            "sans-serif",
                            Typeface.BOLD
                    )
            );

            canvas.drawText(
                    String.format(
                            Locale.getDefault(),
                            "%,d",
                            Math.round(animatedSteps)
                    ),
                    cx,
                    cy + dp(16),
                    paint
            );

            paint.setColor(MUTED);
            paint.setTextSize(dp(10));
            paint.setTypeface(Typeface.DEFAULT);

            canvas.drawText(
                    "STEPS",
                    cx,
                    cy + dp(33),
                    paint
            );

            // ORBIT INDICATORS

            drawIndicator(
                    canvas,
                    cx,
                    cy,
                    outerRadius,
                    dailyProgress,
                    BLUE
            );

            drawIndicator(
                    canvas,
                    cx,
                    cy,
                    innerRadius,
                    milestoneProgress,
                    VIOLET
            );
        }

        private void drawIndicator(
                Canvas canvas,
                float cx,
                float cy,
                float radius,
                float progress,
                int color
        ) {

            double angle = Math.toRadians(
                    -90 + 360 * progress
            );

            float x = cx + radius *
                    (float) Math.cos(angle);

            float y = cy + radius *
                    (float) Math.sin(angle);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);

            canvas.drawCircle(
                    x,
                    y,
                    dp(6),
                    paint
            );

            paint.setColor(WHITE);

            canvas.drawCircle(
                    x,
                    y,
                    dp(2),
                    paint
            );
        }

        @Override
        protected void onDetachedFromWindow() {

            if (activeAnimator != null) {
                activeAnimator.cancel();
            }

            super.onDetachedFromWindow();
        }
    }

    // --------------------------------------------------
    // HEADER
    // --------------------------------------------------

    private void header() {

        LinearLayout r = new LinearLayout(this);

        r.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = new TextView(this);

        brand.setText("STRIDE");
        brand.setTextColor(0xFFFFFFFF);
        brand.setTextSize(25);
        brand.setLetterSpacing(.16f);

        brand.setTypeface(
                null,
                Typeface.BOLD
        );

        r.addView(
                brand,
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1
                )
        );

        TextView avatar = new TextView(this);

        avatar.setText("S");
        avatar.setTextColor(0xFFFFFFFF);
        avatar.setTextSize(18);
        avatar.setGravity(Gravity.CENTER);

        avatar.setBackground(
                round(
                        0xFF30244F,
                        0xFF9875FF,
                        50
                )
        );

        r.addView(
                avatar,
                new LinearLayout.LayoutParams(
                        dp(44),
                        dp(44)
                )
        );

        content.addView(r);

        text(
                "MOVE BEYOND LIMITS",
                10,
                0xFF9875FF,
                true,
                4
        );
    }

    // --------------------------------------------------
    // WALKING PERMISSIONS
    // --------------------------------------------------

    private void requestTrackingPermissions() {

        java.util.ArrayList<String> req =
                new java.util.ArrayList<>();

        if (Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACTIVITY_RECOGNITION
                ) != PackageManager.PERMISSION_GRANTED) {

            req.add(
                    Manifest.permission.ACTIVITY_RECOGNITION
            );
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            req.add(
                    Manifest.permission.ACCESS_FINE_LOCATION
            );
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            req.add(
                    Manifest.permission.ACCESS_COARSE_LOCATION
            );
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {

            req.add(
                    Manifest.permission.POST_NOTIFICATIONS
            );
        }

        if (req.isEmpty()) {

            startManualTracking();

        } else {

            permissions.launch(
                    req.toArray(new String[0])
            );
        }
    }

    // --------------------------------------------------
    // START WALKING
    // --------------------------------------------------

    private void startManualTracking() {

        Intent i = new Intent(
                this,
                TrackingService.class
        );

        i.setAction(
                TrackingService.ACTION_START
        );

        try {

            ContextCompat.startForegroundService(
                    this,
                    i
            );

            tracking = true;

            showHome();

            toast("GPS walking session started");

        } catch (Exception e) {

            tracking = false;

            toast("Unable to start walking session.");
        }
    }

    // --------------------------------------------------
    // STOP WALKING
    // --------------------------------------------------

    private void stopTracking() {

        Intent i = new Intent(
                this,
                TrackingService.class
        );

        i.setAction(
                TrackingService.ACTION_STOP
        );

        try {

            startService(i);

            tracking = false;

            showHome();

            toast("Walking session saved");

        } catch (Exception e) {

            toast("Unable to stop walking session.");
        }
    }

    // --------------------------------------------------
    // LOAD DAILY STATISTICS
    // --------------------------------------------------

    private void loadTodayStats() {

        io.execute(() -> {

            int steps = StrideDatabase.get(this)
                    .strideDao()
                    .getStepsForDay(
                            DateUtils.todayKey()
                    );

            runOnUiThread(() -> {

                todaySteps = Math.max(
                        todaySteps,
                        steps
                );

                initializeMilestoneState();

                updateDashboardValues();
            });
        });
    }

    // --------------------------------------------------
    // HISTORY SCREEN
    // --------------------------------------------------

    private void showHistory() {

        content.removeAllViews();

        header();

        text(
                "Journey history",
                27,
                0xFFFFFFFF,
                true,
                20
        );

        TextView loading = text(
                "Loading saved walks…",
                14,
                0xFF9699AA,
                false,
                10
        );

        io.execute(() -> {

            List<WalkSession> sessions =
                    StrideDatabase.get(this)
                            .strideDao()
                            .getSessions();

            runOnUiThread(() -> {

                content.removeView(loading);

                if (sessions.isEmpty()) {

                    text(
                            "No journeys yet. Start your first walk to see it here.",
                            14,
                            0xFF9699AA,
                            false,
                            10
                    );
                }

                for (WalkSession s : sessions) {

                    MaterialCardView c = card();

                    LinearLayout b = column();

                    b.setPadding(
                            dp(16),
                            dp(16),
                            dp(16),
                            dp(16)
                    );

                    textIn(
                            b,
                            s.title == null
                                    ? "Walking session"
                                    : s.title,
                            17,
                            0xFFFFFFFF,
                            true,
                            8
                    );

                    textIn(
                            b,
                            String.format(
                                    Locale.getDefault(),
                                    "%.2f km  •  %d steps",
                                    s.distanceMeters / 1000,
                                    s.steps
                            ),
                            13,
                            0xFFB7A0FF,
                            false,
                            0
                    );

                    c.addView(b);

                    content.addView(c, matchWrap());
                }
            });
        });
    }

    // --------------------------------------------------
    // INSIGHTS SCREEN
    // --------------------------------------------------

    private void showInsights() {

        content.removeAllViews();

        header();

        text(
                "Insights",
                28,
                0xFFFFFFFF,
                true,
                22
        );

        MaterialCardView c = card();

        LinearLayout b = column();

        b.setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18)
        );

        textIn(
                b,
                "YOUR MOVEMENT DATA",
                11,
                0xFFB7A0FF,
                true,
                12
        );

        textIn(
                b,
                "Your saved walks and daily totals stay on this device.",
                15,
                0xFFFFFFFF,
                false,
                8
        );

        textIn(
                b,
                "A weekly chart and personal records can be added after you have collected activity data.",
                13,
                0xFF9699AA,
                false,
                0
        );

        c.addView(b);

        content.addView(c, matchWrap());

        MaterialButton export = new MaterialButton(this);

        export.setText("VIEW SAVED WALKS");

        export.setOnClickListener(v -> showHistory());

        content.addView(export);
    }

    // --------------------------------------------------
    // PROFILE SCREEN
    // --------------------------------------------------

    private void showProfile() {

        content.removeAllViews();

        header();

        text(
                "Your profile",
                28,
                0xFFFFFFFF,
                true,
                20
        );

        SharedPreferences prefs =
                getSharedPreferences(
                        "stride_settings",
                        MODE_PRIVATE
                );

        Switch auto = new Switch(this);

        auto.setText(
                "Optional automatic GPS location tracking"
        );

        auto.setTextColor(0xFFFFFFFF);

        auto.setChecked(
                prefs.getBoolean("auto_mode", false)
        );

        content.addView(auto);

        text(
                "Daily step counting works independently. This option controls automatic GPS activity tracking, which can use more battery.",
                13,
                0xFF9699AA,
                false,
                18
        );

        auto.setOnCheckedChangeListener(
                (button, checked) -> {

                    Intent i = new Intent(
                            this,
                            TrackingService.class
                    );

                    if (checked) {

                        if (ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED) {

                            auto.setChecked(false);

                            requestTrackingPermissions();

                            toast(
                                    "Grant location permission first."
                            );

                            return;
                        }

                        prefs.edit()
                                .putBoolean("auto_mode", true)
                                .apply();

                        i.setAction(
                                TrackingService.ACTION_AUTO_ON
                        );

                        ContextCompat.startForegroundService(
                                this,
                                i
                        );

                    } else {

                        prefs.edit()
                                .putBoolean("auto_mode", false)
                                .apply();

                        i.setAction(
                                TrackingService.ACTION_AUTO_OFF
                        );

                        startService(i);
                    }
                }
        );

        MaterialButton privacy = new MaterialButton(this);

        privacy.setText(
                "DELETE LOCAL ACTIVITY DATA"
        );

        privacy.setOnClickListener(v ->

                new androidx.appcompat.app.AlertDialog.Builder(this)

                        .setTitle("Delete activity data?")

                        .setMessage(
                                "This permanently deletes locally saved walks and activity totals."
                        )

                        .setNegativeButton("Cancel", null)

                        .setPositiveButton(
                                "Delete",
                                (d, w) -> {

                                    io.execute(() -> {

                                        StrideDatabase.get(this)
                                                .clearAllTables();

                                        getSharedPreferences(
                                                "stride_step_state",
                                                MODE_PRIVATE
                                        ).edit().clear().apply();

                                        getSharedPreferences(
                                                "stride_milestones",
                                                MODE_PRIVATE
                                        ).edit().clear().apply();

                                        runOnUiThread(() -> {

                                            todaySteps = 0;
                                            liveSteps = 0;
                                            liveDistance = 0;

                                            milestoneStateInitialized = false;
                                            milestoneDateKey = "";

                                            initializeMilestoneState();

                                            updateDashboardValues();

                                            toast(
                                                    "Local activity data deleted"
                                            );
                                        });
                                    });
                                }
                        )

                        .show()
        );

        content.addView(privacy);
    }

    // --------------------------------------------------
    // STATISTICS CARD
    // --------------------------------------------------

    private MaterialCardView statCard(
            String title,
            String value,
            String subtitle
    ) {

        MaterialCardView c = card();

        LinearLayout b = column();

        b.setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14)
        );

        textIn(
                b,
                title,
                10,
                0xFF9699AA,
                true,
                12
        );

        TextView valueText = textIn(
                b,
                value,
                22,
                0xFFFFFFFF,
                true,
                7
        );

        if ("DISTANCE".equals(title)) {
            distanceValue = valueText;
        }

        textIn(
                b,
                subtitle,
                10,
                0xFF9699AA,
                false,
                0
        );

        c.addView(b);

        return c;
    }

    // --------------------------------------------------
    // CARD DESIGN
    // --------------------------------------------------

    private MaterialCardView card() {

        MaterialCardView c =
                new MaterialCardView(this);

        c.setCardBackgroundColor(0xFF151722);
        c.setRadius(dp(22));
        c.setStrokeWidth(dp(1));
        c.setStrokeColor(0xFF292B39);
        c.setCardElevation(0);

        LinearLayout.LayoutParams p = matchWrap();

        p.bottomMargin = dp(12);

        c.setLayoutParams(p);

        return c;
    }

    // --------------------------------------------------
    // VERTICAL LAYOUT
    // --------------------------------------------------

    private LinearLayout column() {

        LinearLayout l = new LinearLayout(this);

        l.setOrientation(
                LinearLayout.VERTICAL
        );

        return l;
    }

    // --------------------------------------------------
    // ROW LABEL
    // --------------------------------------------------

    private void rowLabel(
            LinearLayout box,
            String left,
            String right
    ) {

        LinearLayout r = new LinearLayout(this);

        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setOrientation(LinearLayout.HORIZONTAL);

        TextView a = new TextView(this);

        a.setText(left);
        a.setTextSize(11);
        a.setTextColor(0xFFB7A0FF);

        a.setTypeface(
                null,
                Typeface.BOLD
        );

        TextView b = new TextView(this);

        b.setText(right);
        b.setTextSize(10);
        b.setTextColor(0xFF64D9AE);

        b.setTypeface(
                null,
                Typeface.BOLD
        );

        r.addView(
                a,
                new LinearLayout.LayoutParams(
                        0,
                        -2,
                        1
                )
        );

        r.addView(
                b,
                new LinearLayout.LayoutParams(
                        -2,
                        -2
                )
        );

        box.addView(r);
    }

    // --------------------------------------------------
    // GENERAL TEXTVIEW
    // --------------------------------------------------

    private TextView text(
            String value,
            int size,
            int color,
            boolean bold,
            int top
    ) {

        TextView t = new TextView(this);

        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);

        if (bold) {

            t.setTypeface(
                    null,
                    Typeface.BOLD
            );
        }

        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                        -2,
                        -2
                );

        p.topMargin = dp(top);

        content.addView(t, p);

        return t;
    }

    // --------------------------------------------------
    // TEXTVIEW INSIDE ANOTHER LAYOUT
    // --------------------------------------------------

    private TextView textIn(
            LinearLayout parent,
            String value,
            int size,
            int color,
            boolean bold,
            int top
    ) {

        TextView t = new TextView(this);

        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);

        if (bold) {

            t.setTypeface(
                    null,
                    Typeface.BOLD
            );
        }

        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                        -2,
                        -2
                );

        p.topMargin = dp(top);

        parent.addView(t, p);

        return t;
    }

    // --------------------------------------------------
    // LAYOUT PARAMETERS
    // --------------------------------------------------

    private LinearLayout.LayoutParams matchWrap() {

        return new LinearLayout.LayoutParams(
                -1,
                -2
        );
    }

    // --------------------------------------------------
    // ROUNDED BACKGROUND
    // --------------------------------------------------

    private android.graphics.drawable.Drawable round(
            int color,
            int stroke,
            int radius
    ) {

        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();

        d.setColor(color);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), stroke);

        return d;
    }

    // --------------------------------------------------
    // DP CONVERSION
    // --------------------------------------------------

    private int dp(float v) {

        return (int) (
                v * getResources()
                        .getDisplayMetrics()
                        .density + .5f
        );
    }

    // --------------------------------------------------
    // TOAST
    // --------------------------------------------------

    private void toast(String s) {

        Toast.makeText(
                this,
                s,
                Toast.LENGTH_SHORT
        ).show();
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------

    @Override
    protected void onDestroy() {

        milestoneOrbit = null;

        if (receiverRegistered) {

            try {
                unregisterReceiver(updates);
            } catch (Exception ignored) {
            }

            receiverRegistered = false;
        }

        if (io != null) {
            io.shutdown();
        }

        super.onDestroy();
    }
}
