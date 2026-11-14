
package com.stride.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import com.stride.app.data.RoutePoint;
import com.stride.app.data.StrideDatabase;
import com.stride.app.data.WalkSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapActivity extends AppCompatActivity {

    private MapView map;

    private Marker locationMarker;

    private Polyline savedRouteLine;
    private Polyline liveRouteLine;

    private LocationManager locationManager;
    private LocationListener locationListener;

    private final List<GeoPoint> livePoints = new ArrayList<>();

    private ExecutorService databaseExecutor;

    private boolean firstLocationReceived = false;
    private boolean locationUpdatesStarted = false;

    private long selectedSessionId = -1;

    private ActivityResultLauncher<String[]> locationPermissionLauncher;

    private final int DARK_BACKGROUND = Color.rgb(8, 9, 13);
    private final int PURPLE = Color.rgb(152, 117, 255);

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(DARK_BACKGROUND);
        getWindow().setNavigationBarColor(DARK_BACKGROUND);

        Configuration.getInstance().load(
                getApplicationContext(),
                PreferenceManager.getDefaultSharedPreferences(
                        getApplicationContext()
                )
        );

        Configuration.getInstance().setUserAgentValue(
                getPackageName()
        );

        locationManager = (LocationManager)
                getSystemService(Context.LOCATION_SERVICE);

        databaseExecutor = Executors.newSingleThreadExecutor();

        // Read the selected walking session ID.
        selectedSessionId = getIntent().getLongExtra(
                "sessionId",
                getIntent().getLongExtra("session_id", -1)
        );

        locationPermissionLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.RequestMultiplePermissions(),
                        result -> {

                            if (hasLocationPermission()) {
                                startLocationUpdates();
                            } else {
                                Toast.makeText(
                                        this,
                                        "Location permission is required.",
                                        Toast.LENGTH_LONG
                                ).show();
                            }
                        }
                );

        buildScreen();

        // Load the selected saved route, if one was supplied.
        if (selectedSessionId >= 0) {
            loadSavedRoute(selectedSessionId);
        }
    }

    // ----------------------------------------------------
    // SCREEN
    // ----------------------------------------------------

    private void buildScreen() {

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK_BACKGROUND);

        TextView title = new TextView(this);

        title.setText("STRIDE  /  EXPLORE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        title.setPadding(dp(20), dp(22), dp(20), dp(8));

        root.addView(title);

        TextView subtitle = new TextView(this);

        subtitle.setText(
                selectedSessionId >= 0
                        ? "Your walking route."
                        : "Discover your surroundings."
        );

        subtitle.setTextColor(0xFF9699AA);
        subtitle.setTextSize(13);
        subtitle.setPadding(dp(20), 0, dp(20), dp(15));

        root.addView(subtitle);

        Button locationButton = new Button(this);

        locationButton.setText("USE MY LOCATION");
        locationButton.setTextColor(Color.WHITE);

        locationButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(PURPLE)
        );

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(48)
                );

        buttonParams.setMargins(
                dp(16),
                0,
                dp(16),
                dp(12)
        );

        root.addView(locationButton, buttonParams);

        locationButton.setOnClickListener(v -> {

            if (hasLocationPermission()) {

                firstLocationReceived = false;
                startLocationUpdates();

            } else {

                requestLocationPermission();
            }
        });

        // OpenStreetMap.
        map = new MapView(this);

        map.setTileSource(TileSourceFactory.MAPNIK);

        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(true);

        map.setMinZoomLevel(3.0);
        map.setMaxZoomLevel(19.0);

        map.getController().setZoom(5.0);

        map.getController().setCenter(
                new GeoPoint(20.5937, 78.9629)
        );

        LinearLayout.LayoutParams mapParams =
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1
                );

        root.addView(map, mapParams);

        TextView attribution = new TextView(this);

        attribution.setText(
                "© OpenStreetMap contributors"
        );

        attribution.setTextColor(0xFFB7A0FF);
        attribution.setTextSize(10);
        attribution.setGravity(Gravity.CENTER);

        attribution.setPadding(
                dp(10),
                dp(14),
                dp(10),
                dp(14)
        );

        root.addView(attribution);

        setContentView(root);
    }

    // ----------------------------------------------------
    // LOCATION PERMISSION
    // ----------------------------------------------------

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

    private void requestLocationPermission() {

        locationPermissionLauncher.launch(
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }
        );
    }

    // ----------------------------------------------------
    // CURRENT LOCATION
    // ----------------------------------------------------

    private void startLocationUpdates() {

        if (locationUpdatesStarted || !hasLocationPermission()) {
            return;
        }

        try {

            locationUpdatesStarted = true;

            Location lastLocation = null;

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {

                lastLocation = locationManager.getLastKnownLocation(
                        LocationManager.GPS_PROVIDER
                );
            }

            if (lastLocation == null) {

                lastLocation = locationManager.getLastKnownLocation(
                        LocationManager.NETWORK_PROVIDER
                );
            }

            if (lastLocation != null) {
                updateMapLocation(lastLocation);
            }

            locationListener = new LocationListener() {

                @Override
                public void onLocationChanged(Location location) {

                    updateMapLocation(location);
                }
            };

            boolean finePermission =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED;

            if (finePermission &&
                    locationManager.isProviderEnabled(
                            LocationManager.GPS_PROVIDER
                    )) {

                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        3000,
                        5,
                        locationListener
                );
            }

            if (locationManager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
            )) {

                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        3000,
                        5,
                        locationListener
                );
            }

        } catch (SecurityException e) {

            locationUpdatesStarted = false;

            Toast.makeText(
                    this,
                    "Location permission is required.",
                    Toast.LENGTH_SHORT
            ).show();

        } catch (IllegalArgumentException e) {

            locationUpdatesStarted = false;

            Toast.makeText(
                    this,
                    "Location provider is unavailable.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    // ----------------------------------------------------
    // UPDATE CURRENT LOCATION AND LIVE TRAIL
    // ----------------------------------------------------

    private void updateMapLocation(Location location) {

        if (map == null) {
            return;
        }

        GeoPoint point = new GeoPoint(
                location.getLatitude(),
                location.getLongitude()
        );

        if (locationMarker == null) {

            locationMarker = new Marker(map);

            locationMarker.setTitle("Your location");

            locationMarker.setAnchor(
                    Marker.ANCHOR_CENTER,
                    Marker.ANCHOR_BOTTOM
            );

            map.getOverlays().add(locationMarker);
        }

        locationMarker.setPosition(point);

        // Draw the live trail while this map is open.
        if (livePoints.isEmpty()) {

            livePoints.add(point);

        } else {

            GeoPoint previous =
                    livePoints.get(livePoints.size() - 1);

            float[] results = new float[1];

            Location.distanceBetween(
                    previous.getLatitude(),
                    previous.getLongitude(),
                    point.getLatitude(),
                    point.getLongitude(),
                    results
            );

            if (results[0] >= 3) {
                livePoints.add(point);
            }
        }

        if (liveRouteLine == null) {

            liveRouteLine = new Polyline();

            liveRouteLine.setColor(PURPLE);
            liveRouteLine.setWidth(10f);

            map.getOverlays().add(liveRouteLine);
        }

        liveRouteLine.setPoints(new ArrayList<>(livePoints));

        if (!firstLocationReceived) {

            map.getController().animateTo(point);
            map.getController().setZoom(16.0);

            firstLocationReceived = true;

            Toast.makeText(
                    this,
                    "Your location found!",
                    Toast.LENGTH_SHORT
            ).show();
        }

        map.invalidate();
    }

    // ----------------------------------------------------
    // LOAD SAVED WALKING ROUTE
    // ----------------------------------------------------

    private void loadSavedRoute(long sessionId) {

        databaseExecutor.execute(() -> {

            try {

                StrideDatabase db = StrideDatabase.get(
                        getApplicationContext()
                );

                List<RoutePoint> routePoints =
                        db.strideDao().getRoutePoints(sessionId);

                WalkSession session = null;

                List<WalkSession> sessions =
                        db.strideDao().getSessions();

                for (WalkSession item : sessions) {

                    if (item.id == sessionId) {
                        session = item;
                        break;
                    }
                }

                List<GeoPoint> savedPoints = new ArrayList<>();

                for (RoutePoint point : routePoints) {

                    savedPoints.add(
                            new GeoPoint(
                                    point.latitude,
                                    point.longitude
                            )
                    );
                }

                WalkSession finalSession = session;

                runOnUiThread(() -> {

                    if (isFinishing() || isDestroyed()) {
                        return;
                    }

                    if (savedPoints.isEmpty()) {

                        Toast.makeText(
                                this,
                                "No GPS route was saved for this session.",
                                Toast.LENGTH_LONG
                        ).show();

                        return;
                    }

                    drawSavedRoute(savedPoints);

                    if (finalSession != null) {

                        Toast.makeText(
                                this,
                                "Route loaded: "
                                        + finalSession.steps
                                        + " steps",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                });

            } catch (Exception e) {

                runOnUiThread(() -> {

                    if (!isFinishing() && !isDestroyed()) {

                        Toast.makeText(
                                this,
                                "Unable to load walking route.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });
            }
        });
    }

    // ----------------------------------------------------
    // DRAW SAVED ROUTE
    // ----------------------------------------------------

    private void drawSavedRoute(List<GeoPoint> points) {

        if (map == null || points.isEmpty()) {
            return;
        }

        if (savedRouteLine != null) {
            map.getOverlays().remove(savedRouteLine);
        }

        savedRouteLine = new Polyline();

        savedRouteLine.setTitle("Saved walking route");
        savedRouteLine.setColor(PURPLE);
        savedRouteLine.setWidth(12f);

        savedRouteLine.setPoints(points);

        map.getOverlays().add(savedRouteLine);

        // Zoom to show the complete route.
        if (points.size() == 1) {

            map.getController().setZoom(16.0);
            map.getController().animateTo(points.get(0));

        } else {

            map.zoomToBoundingBox(
                    savedRouteLine.getBounds(),
                    true,
                    dp(45)
            );
        }

        map.invalidate();
    }

    // ----------------------------------------------------
    // DP CONVERSION
    // ----------------------------------------------------

    private int dp(float value) {

        return (int) (
                value * getResources()
                        .getDisplayMetrics().density + 0.5f
        );
    }

    // ----------------------------------------------------
    // ACTIVITY LIFECYCLE
    // ----------------------------------------------------

    @Override
    protected void onResume() {

        super.onResume();

        if (map != null) {
            map.onResume();
        }

        if (hasLocationPermission()) {

            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {

        if (locationManager != null && locationListener != null) {

            locationManager.removeUpdates(locationListener);
            locationListener = null;
            locationUpdatesStarted = false;
        }

        if (map != null) {
            map.onPause();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {

        if (locationManager != null && locationListener != null) {

            locationManager.removeUpdates(locationListener);
        }

        if (map != null) {
            map.onDetach();
        }

        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }

        super.onDestroy();
    }
}
