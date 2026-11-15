
package com.stride.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.RadialGradient;
import android.graphics.Shader;
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
import com.stride.app.data.DailyStats;
import com.stride.app.data.StepMinute;
import com.stride.app.tracking.TrackingService;
import com.stride.app.utils.DateUtils;

import java.util.List;
import java.util.Locale;
import java.util.Random;
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
    private TextView remainingStepValue;
    private TextView distanceValue;
    private TextView caloriesValue;

    private MilestoneOrbitView milestoneOrbit;

    private boolean receiverRegistered = false;

    private boolean milestoneStateInitialized = false;
    private String milestoneDateKey = "";

    private int todaySteps = 0;


    private Dialog fireworkDialog;

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

                    todaySteps = intent.getIntExtra(
                            TrackingService.EXTRA_TODAY_STEPS,
                            todaySteps
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
                "VIOLET: 5K MILESTONE     BLUE: YOUR DAILY GOAL",
                10,
                0xFFB7A0FF,
                true,
                8
        );

        previousStepValue = textIn(
                box,
                "Steps tracked automatically: " +
                        formatSteps(todaySteps),
                13,
                0xFFB5B5C5,
                false,
                16
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

        // --------------------------------------------------
        // MANUAL FIREWORK CELEBRATION
        // --------------------------------------------------

        MaterialCardView celebrationCard = card();

        LinearLayout celebrationBox = column();

        celebrationBox.setPadding(
                dp(18),
                dp(16),
                dp(18),
                dp(16)
        );

        textIn(
                celebrationBox,
                "MAKE THIS MOMENT COUNT",
                10,
                0xFFB7A0FF,
                true,
                0
        );

        textIn(
                celebrationBox,
                "Celebrate your progress",
                18,
                0xFFFFFFFF,
                true,
                6
        );

        textIn(
                celebrationBox,
                "Launch your STRIDE firework show whenever you want.",
                12,
                0xFF9699AA,
                false,
                5
        );

        MaterialButton celebrateButton = new MaterialButton(this);

        celebrateButton.setText("✦  PLAY FIREWORK CELEBRATION");
        celebrateButton.setTextColor(0xFFFFFFFF);
        celebrateButton.setTextSize(12);
        celebrateButton.setCornerRadius(dp(15));
        celebrateButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        0xFF7955D9
                )
        );

        LinearLayout.LayoutParams celebrateParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(54)
                );

        celebrateParams.topMargin = dp(14);

        celebrationBox.addView(
                celebrateButton,
                celebrateParams
        );

        celebrateButton.setOnClickListener(v ->
                showFireworkIntro(todaySteps)
        );

        celebrationCard.addView(celebrationBox);

        content.addView(celebrationCard, matchWrap());

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
                        getEstimatedDailyDistanceKm()
                ),
                "Estimated from today's steps"
        );

        MaterialCardView cal = statCard(
                "CALORIES",
                getEstimatedDailyCaloriesText(),
                getCaloriesSubtitle()
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
    }

    // --------------------------------------------------
    // DAILY STEP CALCULATIONS
    // --------------------------------------------------

    private int getPreviousSteps() {
        return Math.max(0, todaySteps);
    }

    private int getDailyGoal() {

        return getSharedPreferences(
                "stride_settings",
                MODE_PRIVATE
        ).getInt("fitness_goal", DAILY_GOAL);
    }

    private int getRemainingSteps() {

        return Math.max(
                0,
                getDailyGoal() - todaySteps
        );
    }

    // Approximate daily walking distance from step count and profile height.
    // Average walking step length is estimated as 0.414 x height.
    private double getEstimatedDailyDistanceKm() {

        SharedPreferences prefs = getSharedPreferences(
                "stride_settings",
                MODE_PRIVATE
        );

        int heightCm = prefs.getInt("height_cm", 0);

        // Use a conservative generic step length until height is provided.
        double stepLengthMeters = heightCm > 0
                ? (heightCm / 100.0) * 0.414
                : 0.72;

        return (Math.max(0, todaySteps) * stepLengthMeters) / 1000.0;
    }

    // Approximate walking energy: about 0.5 kcal per kg per kilometre.
    // This is an estimate, not a medical or exercise-lab measurement.
    private double getEstimatedDailyCalories() {

        SharedPreferences prefs = getSharedPreferences(
                "stride_settings",
                MODE_PRIVATE
        );

        float weightKg = prefs.getFloat("weight_kg", 0f);

        if (weightKg <= 0f) {
            return -1;
        }

        return weightKg * getEstimatedDailyDistanceKm() * 0.5;
    }

    private String getEstimatedDailyCaloriesText() {

        double calories = getEstimatedDailyCalories();

        if (calories < 0) {
            return "— kcal";
        }

        return String.format(
                Locale.getDefault(),
                "%.0f kcal",
                calories
        );
    }

    private String getCaloriesSubtitle() {

        return getEstimatedDailyCalories() < 0
                ? "Add weight in Profile"
                : "Approximate walking burn";
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
                    "Steps tracked automatically: " +
                            formatSteps(todaySteps)
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
                            getEstimatedDailyDistanceKm()
                    )
            );
        }

        if (caloriesValue != null) {
            caloriesValue.setText(getEstimatedDailyCaloriesText());
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

        // Mark normal milestones already reached today.
        for (int milestone : MILESTONES) {

            if (todaySteps >= milestone) {

                editor.putBoolean(
                        today + "_" + milestone,
                        true
                );
            }
        }

        // Mark previously reached firework milestones.
        // This prevents old celebrations from playing
        // when the user opens the app.
        for (int milestone = 5000;
             milestone <= todaySteps;
             milestone += 5000) {

            editor.putBoolean(
                    today + "_firework_" + milestone,
                    true
            );

            if (milestone > Integer.MAX_VALUE - 5000) {
                break;
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
        int newlyUnlockedFirework = 0;

        // ------------------------------------------
        // NORMAL MILESTONES
        // ------------------------------------------

        for (int milestone : MILESTONES) {

            String key = today + "_" + milestone;

            if (todaySteps >= milestone &&
                    !prefs.getBoolean(key, false)) {

                editor.putBoolean(key, true);

                // 5K and 10K use the firework animation.
                if (milestone != 5000 &&
                        milestone != 10000) {

                    newlyUnlocked = milestone;
                }
            }
        }

        // ------------------------------------------
        // FIREWORK MILESTONES
        // 5K, 10K, 15K, 20K, 25K...
        // ------------------------------------------

        for (int milestone = 5000;
             milestone <= todaySteps;
             milestone += 5000) {

            String key =
                    today + "_firework_" + milestone;

            if (!prefs.getBoolean(key, false)) {

                editor.putBoolean(key, true);

                newlyUnlockedFirework = milestone;
            }

            if (milestone > Integer.MAX_VALUE - 5000) {
                break;
            }
        }

        editor.apply();

        // ------------------------------------------
        // SHOW CELEBRATION
        // ------------------------------------------

        if (newlyUnlockedFirework > 0) {

            showFireworkIntro(todaySteps);

        } else if (newlyUnlocked > 0) {

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

        TextView heading = textIn(
                root,
                "MILESTONE UNLOCKED",
                11,
                accent,
                true,
                20
        );

        heading.setGravity(Gravity.CENTER);

        TextView title = textIn(
                root,
                getMilestoneTitle(steps),
                24,
                0xFFFFFFFF,
                true,
                12
        );

        title.setGravity(Gravity.CENTER);

        TextView count = textIn(
                root,
                formatSteps(steps) + " STEPS",
                15,
                accent,
                true,
                10
        );

        count.setGravity(Gravity.CENTER);

        TextView message = textIn(
                root,
                getMilestoneMessage(steps),
                14,
                0xFFB5B5C5,
                false,
                16
        );

        message.setGravity(Gravity.CENTER);

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

            activeAnimator.setDuration(700);

            activeAnimator.setInterpolator(
                    new DecelerateInterpolator(1.5f)
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

            float dailyProgress = Math.min(
                    1f,
                    animatedSteps / getDailyGoal()
            );

            paint.setColor(BLUE);

            canvas.drawArc(
                    outerRect,
                    -90,
                    360 * dailyProgress,
                    false,
                    paint
            );

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

            // DAILY GOAL PERCENTAGE

            paint.setColor(BLUE);
            paint.setTextSize(dp(10));
            paint.setTypeface(Typeface.DEFAULT);

            int percentage = Math.round(
                    Math.min(
                            100f,
                            animatedSteps / getDailyGoal() * 100f
                    )
            );

            canvas.drawText(
                    percentage + "% OF DAILY GOAL",
                    cx,
                    cy + dp(49),
                    paint
            );

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
    // STRIDE FIREWORK INTRO
    // --------------------------------------------------

    private void showFireworkIntro(int steps) {

        if (isFinishing() || isDestroyed()) {
            return;
        }

        // Prevent multiple firework dialogs from opening.
        if (fireworkDialog != null &&
                fireworkDialog.isShowing()) {
            return;
        }

        fireworkDialog = new Dialog(this);

        FireworkIntroView fireworkView =
                new FireworkIntroView(this, steps);

        fireworkDialog.setContentView(fireworkView);

        fireworkDialog.setCancelable(false);

        Window window = fireworkDialog.getWindow();

        if (window != null) {

            window.setBackgroundDrawable(
                    new ColorDrawable(Color.TRANSPARENT)
            );

            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
            );

            window.setStatusBarColor(0xFF08090D);
            window.setNavigationBarColor(0xFF08090D);
        }

        fireworkDialog.show();

        if (window != null) {

            window.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
            );
        }

        fireworkView.startAnimation(() -> {

            if (fireworkDialog != null &&
                    fireworkDialog.isShowing()) {

                fireworkDialog.dismiss();
            }

            fireworkDialog = null;
        });
    }

    // --------------------------------------------------
    // BIG STRIDE FIRECRACKER - 5 SECONDS
    // --------------------------------------------------

    private class FireworkIntroView extends View {

        private final Paint paint =
                new Paint(Paint.ANTI_ALIAS_FLAG);

        private final int stepCount;

        private final int[] colors = {
                0xFF38BDF8,
                0xFF9875FF,
                0xFFA78BFA,
                0xFFFFFFFF,
                0xFF64D9AE,
                0xFFFFB84D,
                0xFFFF668C,
                0xFFFFE082
        };

        private static final int BURST_COUNT = 3;
        private static final int PARTICLES_PER_BURST = 110;

        private final float[][] particleAngles =
                new float[BURST_COUNT][PARTICLES_PER_BURST];

        private final float[][] particleSpeeds =
                new float[BURST_COUNT][PARTICLES_PER_BURST];

        private final float[][] particleSizes =
                new float[BURST_COUNT][PARTICLES_PER_BURST];

        private final int[][] particleColors =
                new int[BURST_COUNT][PARTICLES_PER_BURST];

        private final float[] burstX = new float[BURST_COUNT];
        private final float[] burstY = new float[BURST_COUNT];

        private final float[] burstStart = {
                0.20f,
                0.43f,
                0.63f
        };

        private final float[] burstDuration = {
                0.34f,
                0.34f,
                0.34f
        };

        private float progress = 0f;

        private ValueAnimator animator;

        private Runnable onFinished;

        public FireworkIntroView(
                Context context,
                int steps
        ) {

            super(context);

            stepCount = Math.max(0, steps);

            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            Random random = new Random();

            for (int burst = 0; burst < BURST_COUNT; burst++) {

                for (int i = 0; i < PARTICLES_PER_BURST; i++) {

                    particleAngles[burst][i] =
                            (float) (random.nextDouble() * Math.PI * 2);

                    particleSpeeds[burst][i] =
                            dp(110) + random.nextFloat() * dp(260);

                    particleSizes[burst][i] =
                            dp(2) + random.nextFloat() * dp(4);

                    particleColors[burst][i] =
                            colors[random.nextInt(colors.length)];
                }
            }
        }

        // --------------------------------------------------
        // START 5 SECOND ANIMATION
        // --------------------------------------------------

        public void startAnimation(Runnable finished) {

            onFinished = finished;

            animator = ValueAnimator.ofFloat(0f, 1f);

            animator.setDuration(5000);

            animator.setInterpolator(
                    new DecelerateInterpolator(0.7f)
            );

            animator.addUpdateListener(animation -> {

                progress =
                        (float) animation.getAnimatedValue();

                invalidate();
            });

            animator.addListener(
                    new AnimatorListenerAdapter() {

                        @Override
                        public void onAnimationEnd(
                                Animator animation
                        ) {

                            if (onFinished != null) {
                                onFinished.run();
                            }
                        }
                    }
            );

            animator.start();
        }

        // --------------------------------------------------
        // DRAW ANIMATION
        // --------------------------------------------------

        @Override
        protected void onDraw(Canvas canvas) {

            super.onDraw(canvas);

            float width = getWidth();
            float height = getHeight();

            float cx = width / 2f;
            float cy = height * 0.43f;

            // BACKGROUND

            canvas.drawColor(0xFF08090D);

            // BACKGROUND ENERGY GLOW

            paint.setStyle(Paint.Style.FILL);

            paint.setShader(
                    new RadialGradient(
                            cx,
                            cy,
                            dp(310),
                            new int[]{
                                    0x553B78C5,
                                    0x332D3C91,
                                    0x221D174F,
                                    0x0008090D
                            },
                            null,
                            Shader.TileMode.CLAMP
                    )
            );

            canvas.drawCircle(
                    cx,
                    cy,
                    dp(310),
                    paint
            );

            paint.setShader(null);

            // FIREWORK LOCATIONS

            burstX[0] = cx;
            burstY[0] = cy;

            burstX[1] = cx - width * 0.27f;
            burstY[1] = cy - height * 0.12f;

            burstX[2] = cx + width * 0.27f;
            burstY[2] = cy - height * 0.12f;

            // --------------------------------------------------
            // ROCKET LAUNCH
            // --------------------------------------------------

            float launchProgress =
                    Math.min(1f, progress / 0.20f);

            if (progress < 0.23f) {

                float rocketY =
                        height * 0.91f -
                                launchProgress * height * 0.49f;

                // Rocket trail glow

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(dp(12));
                paint.setColor(0x4438BDF8);

                canvas.drawLine(
                        cx,
                        rocketY + dp(70),
                        cx,
                        rocketY,
                        paint
                );

                // Main rocket trail

                paint.setStrokeWidth(dp(4));
                paint.setColor(0xFF38BDF8);

                canvas.drawLine(
                        cx,
                        rocketY + dp(45),
                        cx,
                        rocketY,
                        paint
                );

                // Rocket head

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);

                canvas.drawCircle(
                        cx,
                        rocketY,
                        dp(7),
                        paint
                );

                // Rocket glow

                paint.setColor(0x6638BDF8);

                canvas.drawCircle(
                        cx,
                        rocketY,
                        dp(23),
                        paint
                );
            }

            // --------------------------------------------------
            // THREE MASSIVE FIREWORK EXPLOSIONS
            // --------------------------------------------------

            for (int burst = 0; burst < BURST_COUNT; burst++) {

                float start = burstStart[burst];

                float duration = burstDuration[burst];

                float burstProgress =
                        (progress - start) / duration;

                if (burstProgress < 0f ||
                        burstProgress > 1f) {

                    continue;
                }

                float expansion =
                        1f - (1f - burstProgress)
                                * (1f - burstProgress);

                float fade =
                        1f - burstProgress;

                float explosionRadius =
                        dp(15) + expansion * dp(235);

                float explosionX = burstX[burst];
                float explosionY = burstY[burst];

                // BIG CENTRAL FLASH

                if (burstProgress < 0.20f) {

                    float flash =
                            1f - burstProgress / 0.20f;

                    paint.setStyle(Paint.Style.FILL);

                    paint.setColor(
                            ((int) (255 * flash) << 24)
                                    | 0x00FFFFFF
                    );

                    canvas.drawCircle(
                            explosionX,
                            explosionY,
                            dp(65) * flash,
                            paint
                    );

                    paint.setColor(
                            ((int) (100 * flash) << 24)
                                    | 0x0038BDF8
                    );

                    canvas.drawCircle(
                            explosionX,
                            explosionY,
                            dp(115) * flash,
                            paint
                    );
                }

                // EXPLOSION RING

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3));
                paint.setColor(
                        ((int) (180 * fade) << 24)
                                | 0x009875FF
                );

                canvas.drawCircle(
                        explosionX,
                        explosionY,
                        explosionRadius * 0.70f,
                        paint
                );

                // PARTICLES

                for (int i = 0; i < PARTICLES_PER_BURST; i++) {

                    double angle =
                            particleAngles[burst][i];

                    float distance =
                            explosionRadius *
                                    (particleSpeeds[burst][i] / dp(370));

                    float gravity =
                            burstProgress * burstProgress * dp(105);

                    float x =
                            explosionX +
                                    (float) Math.cos(angle) * distance;

                    float y =
                            explosionY +
                                    (float) Math.sin(angle) * distance
                                    + gravity;

                    int alpha = Math.max(
                            0,
                            Math.min(
                                    255,
                                    (int) (255 * fade)
                            )
                    );

                    int particleColor =
                            particleColors[burst][i];

                    // PARTICLE GLOW

                    paint.setStyle(Paint.Style.FILL);

                    paint.setColor(
                            (Math.max(0, alpha / 4) << 24)
                                    | (particleColor & 0x00FFFFFF)
                    );

                    paint.setShadowLayer(
                            dp(15),
                            0,
                            0,
                            particleColor
                    );

                    canvas.drawCircle(
                            x,
                            y,
                            particleSizes[burst][i] * 3f * fade,
                            paint
                    );

                    paint.clearShadowLayer();

                    // BRIGHT PARTICLE CORE

                    paint.setColor(
                            (alpha << 24)
                                    | (particleColor & 0x00FFFFFF)
                    );

                    canvas.drawCircle(
                            x,
                            y,
                            Math.max(
                                    dp(0.5f),
                                    particleSizes[burst][i] * fade
                            ),
                            paint
                    );

                    // LONG SPARK TRAIL

                    paint.setStyle(Paint.Style.STROKE);

                    paint.setStrokeCap(Paint.Cap.ROUND);

                    paint.setStrokeWidth(
                            Math.max(
                                    dp(1),
                                    dp(3) * fade
                            )
                    );

                    paint.setColor(
                            (Math.max(0, alpha / 2) << 24)
                                    | (particleColor & 0x00FFFFFF)
                    );

                    float tailLength =
                            dp(30) * fade;

                    float tailX =
                            x - (float) Math.cos(angle) * tailLength;

                    float tailY =
                            y - (float) Math.sin(angle) * tailLength;

                    canvas.drawLine(
                            tailX,
                            tailY,
                            x,
                            y,
                            paint
                    );
                }
            }

            // --------------------------------------------------
            // EXTRA ENERGY FLASH
            // --------------------------------------------------

            if (progress > 0.18f && progress < 0.28f) {

                float flash =
                        1f - (progress - 0.18f) / 0.10f;

                paint.setStyle(Paint.Style.STROKE);

                paint.setStrokeWidth(dp(5));

                paint.setColor(
                        ((int) (200 * flash) << 24)
                                | 0x00FFFFFF
                );

                canvas.drawCircle(
                        cx,
                        cy,
                        dp(90) + dp(160) * (1f - flash),
                        paint
                );
            }

            // --------------------------------------------------
            // STEP COUNT REVEAL
            // --------------------------------------------------

            if (progress > 0.42f) {

                float reveal =
                        Math.min(
                                1f,
                                (progress - 0.42f) / 0.20f
                        );

                float scale =
                        0.65f + 0.35f * reveal;

                canvas.save();

                canvas.scale(
                        scale,
                        scale,
                        cx,
                        cy + dp(55)
                );

                // HEADING

                paint.setStyle(Paint.Style.FILL);

                paint.setColor(0xFFB5B5C5);

                paint.setTextAlign(Paint.Align.CENTER);

                paint.setTextSize(dp(13));

                paint.setTypeface(Typeface.DEFAULT);

                canvas.drawText(
                        "YOUR DAILY MOVEMENT",
                        cx,
                        cy + dp(65),
                        paint
                );

                // STEP COUNT

                paint.setColor(0xFFFFFFFF);

                paint.setTextSize(dp(47));

                paint.setTypeface(
                        Typeface.create(
                                "sans-serif",
                                Typeface.BOLD
                        )
                );

                paint.setShadowLayer(
                        dp(22),
                        0,
                        0,
                        0xFF38BDF8
                );

                canvas.drawText(
                        String.format(
                                Locale.getDefault(),
                                "%,d",
                                stepCount
                        ),
                        cx,
                        cy + dp(125),
                        paint
                );

                paint.clearShadowLayer();

                // STEPS LABEL

                paint.setColor(0xFF38BDF8);

                paint.setTextSize(dp(16));

                paint.setTypeface(Typeface.DEFAULT_BOLD);

                canvas.drawText(
                        "STEPS",
                        cx,
                        cy + dp(158),
                        paint
                );

                // DAILY MESSAGE

                paint.setColor(0xFFA78BFA);

                paint.setTextSize(dp(12));

                paint.setTypeface(Typeface.DEFAULT);

                canvas.drawText(
                        "KEEP MOVING. KEEP WINNING.",
                        cx,
                        cy + dp(195),
                        paint
                );

                canvas.restore();
            }

            // --------------------------------------------------
            // STRIDE BRAND
            // --------------------------------------------------

            paint.setStyle(Paint.Style.FILL);

            paint.setColor(0xFFFFFFFF);

            paint.setTextAlign(Paint.Align.CENTER);

            paint.setTextSize(dp(25));

            paint.setTypeface(
                    Typeface.create(
                            "sans-serif",
                            Typeface.BOLD
                    )
            );

            canvas.drawText(
                    "STRIDE",
                    cx,
                    height * 0.13f,
                    paint
            );

            paint.setColor(0xFF9875FF);

            paint.setTextSize(dp(10));

            paint.setTypeface(Typeface.DEFAULT_BOLD);

            canvas.drawText(
                    "MOVE BEYOND LIMITS",
                    cx,
                    height * 0.13f + dp(22),
                    paint
            );

            // --------------------------------------------------
            // FINAL FADE INDICATOR
            // --------------------------------------------------

            if (progress > 0.90f) {

                float alpha =
                        (progress - 0.90f) / 0.10f;

                paint.setColor(
                        ((int) (180 * alpha) << 24)
                                | 0x00FFFFFF
                );

                paint.setTextSize(dp(10));

                paint.setTypeface(Typeface.DEFAULT);

                canvas.drawText(
                        "LET'S GET MOVING",
                        cx,
                        height * 0.88f,
                        paint
                );
            }
        }

        // --------------------------------------------------
        // CLEANUP
        // --------------------------------------------------

        @Override
        protected void onDetachedFromWindow() {

            if (animator != null) {

                animator.cancel();
                animator = null;
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

                if (isFinishing() || isDestroyed()) {
                    return;
                }

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

                if (isFinishing() || isDestroyed()) {
                    return;
                }

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

        text("Your Insights", 28, 0xFFFFFFFF, true, 22);
        text("Understand your movement. Improve every day.",
                13, 0xFF9699AA, false, 5);

        TextView loading = text(
                "Loading your activity data...",
                14,
                0xFFB7A0FF,
                false,
                20
        );

        io.execute(() -> {

            String today = DateUtils.todayKey();

            List<StepMinute> minuteData =
                    StrideDatabase.get(this)
                            .strideDao()
                            .getStepMinutesForDay(today);

            List<DailyStats> weeklyData =
                    StrideDatabase.get(this)
                            .strideDao()
                            .getLastSevenDays();

            runOnUiThread(() -> {

                if (isFinishing() || isDestroyed()) return;

                content.removeView(loading);

                text("TODAY'S OVERVIEW", 11, 0xFFB7A0FF, true, 20);

                LinearLayout overview = new LinearLayout(this);
                overview.setOrientation(LinearLayout.HORIZONTAL);

                overview.addView(
                        statCard("STEPS", formatSteps(todaySteps), "Today's movement"),
                        new LinearLayout.LayoutParams(0, dp(116), 1)
                );

                LinearLayout.LayoutParams distanceParams =
                        new LinearLayout.LayoutParams(0, dp(116), 1);
                distanceParams.leftMargin = dp(10);

                overview.addView(
                        statCard(
                                "DISTANCE",
                                String.format(Locale.getDefault(), "%.2f km", getEstimatedDailyDistanceKm()),
                                "Estimated distance"
                        ),
                        distanceParams
                );

                content.addView(overview, matchWrap());

                LinearLayout secondRow = new LinearLayout(this);
                secondRow.setOrientation(LinearLayout.HORIZONTAL);

                secondRow.addView(
                        statCard("CALORIES", getEstimatedDailyCaloriesText(), getCaloriesSubtitle()),
                        new LinearLayout.LayoutParams(0, dp(116), 1)
                );

                LinearLayout.LayoutParams goalParams =
                        new LinearLayout.LayoutParams(0, dp(116), 1);
                goalParams.leftMargin = dp(10);

                int goalPercent = Math.min(
                        100,
                        Math.round(todaySteps * 100f / Math.max(1, getDailyGoal()))
                );

                secondRow.addView(
                        statCard("GOAL", goalPercent + "%", "Daily goal progress"),
                        goalParams
                );

                content.addView(secondRow, matchWrap());

                text("Steps per minute", 20, 0xFFFFFFFF, true, 26);
                text("See when you were most active today.",
                        12, 0xFF9699AA, false, 5);

                MaterialCardView minuteCard = card();
                LinearLayout minuteBox = column();
                minuteBox.setPadding(dp(16), dp(18), dp(16), dp(18));

                minuteBox.addView(
                        new MinuteStepsChartView(this, minuteData),
                        new LinearLayout.LayoutParams(-1, dp(210))
                );

                minuteCard.addView(minuteBox);
                content.addView(minuteCard, matchWrap());

                text("Your last 7 days", 20, 0xFFFFFFFF, true, 25);
                text("Compare your daily step counts.",
                        12, 0xFF9699AA, false, 5);

                int totalSteps = 0;
                int bestSteps = 0;

                for (DailyStats day : weeklyData) {
                    totalSteps += Math.max(0, day.steps);
                    bestSteps = Math.max(bestSteps, day.steps);
                }

                int averageSteps = weeklyData.isEmpty()
                        ? 0
                        : totalSteps / weeklyData.size();

                MaterialCardView weeklyCard = card();
                LinearLayout weeklyBox = column();
                weeklyBox.setPadding(dp(16), dp(18), dp(16), dp(18));

                weeklyBox.addView(
                        new WeeklyStepsChartView(this, weeklyData),
                        new LinearLayout.LayoutParams(-1, dp(210))
                );

                weeklyCard.addView(weeklyBox);
                content.addView(weeklyCard, matchWrap());

                text("Weekly summary", 20, 0xFFFFFFFF, true, 24);

                MaterialCardView summaryCard = card();
                LinearLayout summaryBox = column();
                summaryBox.setPadding(dp(18), dp(18), dp(18), dp(18));

                addInsightRow(summaryBox, "TOTAL STEPS", formatSteps(totalSteps));
                addInsightRow(summaryBox, "DAILY AVERAGE", formatSteps(averageSteps));
                addInsightRow(
                        summaryBox,
                        "BEST DAY",
                        bestSteps > 0 ? formatSteps(bestSteps) + " steps" : "No activity yet"
                );
                addInsightRow(summaryBox, "DAYS WITH DATA", String.valueOf(weeklyData.size()));

                summaryCard.addView(summaryBox);
                content.addView(summaryCard, matchWrap());

                MaterialButton historyButton = new MaterialButton(this);
                historyButton.setText("VIEW WALKING HISTORY");
                historyButton.setOnClickListener(v -> showHistory());
                content.addView(historyButton, matchWrap());

                if (minuteData.isEmpty()) {
                    text(
                            "Your minute-by-minute graph will appear as STRIDE records your steps. Keep daily step counting enabled.",
                            12,
                            0xFF9699AA,
                            false,
                            8
                    );
                }
            });
        });
    }

    // --------------------------------------------------
    // INSIGHTS SUMMARY ROW
    // --------------------------------------------------

    private void addInsightRow(
            LinearLayout parent,
            String label,
            String value
    ) {

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextColor(0xFF9699AA);
        left.setTextSize(12);
        left.setTypeface(null, Typeface.BOLD);

        TextView right = new TextView(this);
        right.setText(value);
        right.setTextColor(0xFFFFFFFF);
        right.setTextSize(14);
        right.setTypeface(null, Typeface.BOLD);
        right.setGravity(Gravity.END);

        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(right, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(18);
        parent.addView(row, params);
    }

    // --------------------------------------------------
    // MINUTE-WISE STEP GRAPH
    // --------------------------------------------------

    private class MinuteStepsChartView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<StepMinute> data;

        MinuteStepsChartView(Context context, List<StepMinute> data) {
            super(context);
            this.data = data;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float width = getWidth();
            float height = getHeight();
            float left = dp(35);
            float right = width - dp(10);
            float top = dp(20);
            float bottom = height - dp(35);
            float chartWidth = right - left;
            float chartHeight = bottom - top;

            if (data == null || data.isEmpty()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF9699AA);
                paint.setTextSize(dp(11));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(
                        "Start walking to generate your graph",
                        width / 2f,
                        height / 2f,
                        paint
                );
                return;
            }

            int maxSteps = 0;
            for (StepMinute minute : data) {
                maxSteps = Math.max(maxSteps, minute.steps);
            }
            maxSteps = Math.max(10, maxSteps);

            // Horizontal guide lines and Y-axis values.
            for (int i = 0; i <= 4; i++) {
                float y = top + chartHeight * i / 4f;
                paint.setColor(0xFF292B39);
                paint.setStrokeWidth(dp(1));
                canvas.drawLine(left, y, right, y, paint);

                paint.setColor(0xFF9699AA);
                paint.setTextSize(dp(9));
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(
                        String.valueOf(Math.round(maxSteps * (4 - i) / 4f)),
                        left - dp(7),
                        y + dp(4),
                        paint
                );
            }

            // Use the local calendar day containing the records.
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.setTimeInMillis(data.get(0).minuteStartMillis);
            calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
            calendar.set(java.util.Calendar.MINUTE, 0);
            calendar.set(java.util.Calendar.SECOND, 0);
            calendar.set(java.util.Calendar.MILLISECOND, 0);
            long midnight = calendar.getTimeInMillis();

            float previousX = -1f;
            float previousY = -1f;

            paint.setColor(0xFF9875FF);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);

            for (StepMinute minute : data) {
                float minuteOfDay =
                        (minute.minuteStartMillis - midnight) / 60000f;

                float x = left + (minuteOfDay / 1440f) * chartWidth;
                float y = bottom - (minute.steps / (float) maxSteps) * chartHeight;

                // Break the line across long gaps so idle hours aren't misleadingly connected.
                if (previousX >= 0 && x - previousX <= chartWidth * (5f / 1440f)) {
                    canvas.drawLine(previousX, previousY, x, y, paint);
                }

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFA78BFA);
                canvas.drawCircle(x, y, dp(3), paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0xFF9875FF);

                previousX = x;
                previousY = y;
            }

            // Time labels.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF9699AA);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(9));

            String[] labels = {"12 AM", "6 AM", "12 PM", "6 PM", "12 AM"};
            for (int i = 0; i < labels.length; i++) {
                float x = left + chartWidth * i / 4f;
                canvas.drawText(labels[i], x, height - dp(8), paint);
            }
        }
    }

    // --------------------------------------------------
    // WEEKLY STEP GRAPH
    // --------------------------------------------------

    private class WeeklyStepsChartView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<DailyStats> data;

        WeeklyStepsChartView(Context context, List<DailyStats> data) {
            super(context);
            this.data = data;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float width = getWidth();
            float height = getHeight();
            float left = dp(12);
            float top = dp(25);
            float bottom = height - dp(35);
            float chartHeight = bottom - top;

            if (data == null || data.isEmpty()) {
                paint.setColor(0xFF9699AA);
                paint.setTextSize(dp(11));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(
                        "Your weekly activity will appear here",
                        width / 2f,
                        height / 2f,
                        paint
                );
                return;
            }

            int maxSteps = 0;
            for (DailyStats day : data) {
                maxSteps = Math.max(maxSteps, day.steps);
            }
            maxSteps = Math.max(1000, maxSteps);

            int count = Math.min(7, data.size());
            float availableWidth = width - left - dp(10);
            float slotWidth = availableWidth / 7f;

            // DAO returns newest first; reverse for chronological display.
            for (int i = 0; i < count; i++) {
                DailyStats day = data.get(count - 1 - i);
                float centerX = left + slotWidth * (i + 0.5f);
                float barHeight = (day.steps / (float) maxSteps) * chartHeight;
                float barTop = bottom - barHeight;

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(i == count - 1 ? 0xFF38BDF8 : 0xFF9875FF);

                RectF bar = new RectF(
                        centerX - dp(10),
                        barTop,
                        centerX + dp(10),
                        bottom
                );
                canvas.drawRoundRect(bar, dp(7), dp(7), paint);

                paint.setColor(0xFFB5B5C5);
                paint.setTextSize(dp(9));
                paint.setTextAlign(Paint.Align.CENTER);

                String value = day.steps >= 1000
                        ? String.format(Locale.getDefault(), "%.1fk", day.steps / 1000f)
                        : String.valueOf(day.steps);

                canvas.drawText(value, centerX, Math.max(top - dp(7), barTop - dp(7)), paint);

                paint.setColor(0xFF9699AA);
                paint.setTextSize(dp(9));
                canvas.drawText(getShortDateLabel(day.dateKey), centerX, height - dp(10), paint);
            }
        }
    }

    // --------------------------------------------------
    // SHORT DATE LABEL FOR WEEKLY CHART
    // --------------------------------------------------

    private String getShortDateLabel(String dateKey) {

        if (dateKey == null || dateKey.length() < 5) return "--";

        // Supports both yyyy-MM-dd and yyyyMMdd date keys.
        if (dateKey.length() >= 10 && dateKey.charAt(4) == '-') {
            return dateKey.substring(5, 10);
        }

        if (dateKey.length() == 8) {
            return dateKey.substring(4, 6) + "/" + dateKey.substring(6, 8);
        }

        return dateKey.substring(Math.max(0, dateKey.length() - 5));
    }

    // --------------------------------------------------
    // PROFILE SCREEN
    // --------------------------------------------------

    private void showProfile() {

        content.removeAllViews();
        header();

        text("Your profile", 28, 0xFFFFFFFF, true, 20);

        SharedPreferences prefs = getSharedPreferences(
                "stride_settings",
                MODE_PRIVATE
        );

        // PERSONAL FITNESS PROFILE
        MaterialCardView fitnessCard = card();
        LinearLayout fitnessBox = column();
        fitnessBox.setPadding(dp(18), dp(18), dp(18), dp(18));

        textIn(fitnessBox, "PERSONAL FITNESS", 11,
                0xFFB7A0FF, true, 0);

        textIn(fitnessBox, "Your body. Your goals.", 19,
                0xFFFFFFFF, true, 8);

        int goal = getDailyGoal();
        int height = prefs.getInt("height_cm", 0);
        float weight = prefs.getFloat("weight_kg", 0f);
        int age = prefs.getInt("age_years", 0);

        String heightText = height > 0 ? height + " cm" : "Not set";
        String weightText = weight > 0
                ? String.format(Locale.getDefault(), "%.1f kg", weight)
                : "Not set";
        String ageText = age > 0 ? age + " years" : "Not set";

        textIn(fitnessBox, "Daily step goal     " +
                        String.format(Locale.getDefault(), "%,d steps", goal),
                14, 0xFFFFFFFF, false, 16);

        textIn(fitnessBox, "Height     " + heightText,
                14, 0xFFBFC1CF, false, 12);

        textIn(fitnessBox, "Weight     " + weightText,
                14, 0xFFBFC1CF, false, 12);

        textIn(fitnessBox, "Age     " + ageText,
                14, 0xFFBFC1CF, false, 12);

        MaterialButton editProfile = new MaterialButton(this);
        editProfile.setText("EDIT FITNESS PROFILE");
        editProfile.setOnClickListener(v -> showFitnessProfileDialog());

        LinearLayout.LayoutParams editParams = matchWrap();
        editParams.topMargin = dp(16);
        fitnessBox.addView(editProfile, editParams);

        fitnessCard.addView(fitnessBox);
        content.addView(fitnessCard);

        // AUTOMATIC DAILY TRACKING — no start button or GPS session required.
        MaterialCardView trackingCard = card();
        LinearLayout trackingBox = column();
        trackingBox.setPadding(dp(18), dp(16), dp(18), dp(16));

        textIn(trackingBox, "AUTOMATIC DAILY TRACKING", 11,
                0xFFB7A0FF, true, 8);

        textIn(trackingBox, "● STEP TRACKING ENABLED", 15,
                0xFF64D9AE, true, 8);

        textIn(trackingBox,
                "STRIDE counts steps in the background and saves daily steps, estimated distance, active-minute data and estimated calories. GPS is not required for these daily statistics.",
                13, 0xFF9699AA, false, 0);

        trackingCard.addView(trackingBox);
        content.addView(trackingCard);

        // LOCAL ACTIVITY DATA
        MaterialButton privacy = new MaterialButton(this);
        privacy.setText("DELETE LOCAL ACTIVITY DATA");

        privacy.setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Delete activity data?")
                        .setMessage(
                                "This permanently deletes locally saved walks and activity totals. Your fitness profile will remain saved."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, w) -> {

                            io.execute(() -> {

                                StrideDatabase.get(this).clearAllTables();

                                getSharedPreferences(
                                        "stride_step_state",
                                        MODE_PRIVATE
                                ).edit().clear().apply();

                                getSharedPreferences(
                                        "stride_milestones",
                                        MODE_PRIVATE
                                ).edit().clear().apply();

                                getSharedPreferences(
                                        "stride_daily_metrics",
                                        MODE_PRIVATE
                                ).edit().clear().apply();

                                runOnUiThread(() -> {

                                    if (isFinishing() || isDestroyed()) return;

                                    todaySteps = 0;

                                    milestoneStateInitialized = false;
                                    milestoneDateKey = "";

                                    initializeMilestoneState();
                                    updateDashboardValues();
                                    showProfile();

                                    toast("Local activity data deleted");
                                });
                            });
                        })
                        .show()
        );

        content.addView(privacy);
    }

    // --------------------------------------------------
    // FITNESS PROFILE EDITOR
    // --------------------------------------------------

    private void showFitnessProfileDialog() {

        SharedPreferences prefs = getSharedPreferences(
                "stride_settings",
                MODE_PRIVATE
        );

        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText goalInput = fitnessInput(
                "Daily step goal",
                String.valueOf(getDailyGoal()),
                false
        );

        int savedHeight = prefs.getInt("height_cm", 0);
        float savedWeight = prefs.getFloat("weight_kg", 0f);
        int savedAge = prefs.getInt("age_years", 0);

        EditText heightInput = fitnessInput(
                "Height in cm",
                savedHeight == 0 ? "" : String.valueOf(savedHeight),
                false
        );

        EditText weightInput = fitnessInput(
                "Weight in kg",
                savedWeight == 0f ? "" : String.valueOf(savedWeight),
                true
        );

        EditText ageInput = fitnessInput(
                "Age in years",
                savedAge == 0 ? "" : String.valueOf(savedAge),
                false
        );

        addFitnessField(form, "DAILY STEP GOAL", goalInput);
        addFitnessField(form, "HEIGHT (CM)", heightInput);
        addFitnessField(form, "WEIGHT (KG)", weightInput);
        addFitnessField(form, "AGE", ageInput);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Fitness profile")
                        .setView(scroll)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", null)
                        .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(v -> {

                            try {

                                String goalText = goalInput.getText().toString().trim();
                                String heightText = heightInput.getText().toString().trim();
                                String weightText = weightInput.getText().toString().trim();
                                String ageText = ageInput.getText().toString().trim();

                                int goal = Integer.parseInt(goalText);
                                int height = heightText.isEmpty()
                                        ? 0 : Integer.parseInt(heightText);
                                float weight = weightText.isEmpty()
                                        ? 0f : Float.parseFloat(weightText);
                                int age = ageText.isEmpty()
                                        ? 0 : Integer.parseInt(ageText);

                                if (goal < 1000 || goal > 100000 ||
                                        (height != 0 && (height < 80 || height > 250)) ||
                                        (weight != 0f && (weight < 20f || weight > 300f)) ||
                                        (age != 0 && (age < 5 || age > 110))) {

                                    toast("Please enter valid fitness details.");
                                    return;
                                }

                                prefs.edit()
                                        .putInt("fitness_goal", goal)
                                        .putInt("height_cm", height)
                                        .putFloat("weight_kg", weight)
                                        .putInt("age_years", age)
                                        .apply();

                                dialog.dismiss();
                                showProfile();
                                updateDashboardValues();
                                toast("Fitness profile saved");

                            } catch (NumberFormatException e) {
                                toast("Please check the entered values.");
                            }
                        })
        );

        dialog.show();
    }

    private EditText fitnessInput(
            String hint,
            String value,
            boolean decimal
    ) {

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setHint(hint);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF77798A);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackground(round(0xFF202231, 0xFF34364A, 12));

        input.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER |
                        (decimal
                                ? android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                                : 0)
        );

        return input;
    }

    private void addFitnessField(
            LinearLayout parent,
            String label,
            EditText input
    ) {

        textIn(parent, label, 10, 0xFF9699AA, true, 14);

        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        parent.addView(input, params);
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
        } else if ("CALORIES".equals(title)) {
            caloriesValue = valueText;
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

        if (fireworkDialog != null &&
                fireworkDialog.isShowing()) {

            fireworkDialog.dismiss();
            fireworkDialog = null;
        }

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
