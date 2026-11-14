# STRIDE — Android walking and GPS tracker

Java + XML Android Studio project. Package: `com.stride.app`.

## Included
- Premium dark Android interface and bottom navigation
- Android step-counter sensor integration in a foreground tracking service
- Manual GPS walk start/stop flow
- Optional automatic tracking toggle
- Dark Carto map preview using osmdroid
- Room database for local sessions and daily totals
- History, insights and profile screens
- Local activity-data deletion control

## Requirements
- Android Studio with JDK 17 for Gradle / AGP 8.7.2
- Android SDK Platform 35
- Physical Android device recommended for step sensor and GPS testing
- Internet access for map tiles

## Open and run
1. Extract the ZIP.
2. Open the `STRIDE-Android` folder in Android Studio.
3. Allow Gradle sync and install required SDK Platform 35 if prompted.
4. Run on a physical Android phone.
5. Grant activity recognition, location and notification permissions when requested.

## Important implementation notes
- The map uses Carto dark raster tiles through osmdroid. It needs internet access; map tiles are not bundled offline.
- The initial map view is centered on Mumbai as a neutral preview location. It is not a live location fix.
- The current service records step counts relative to the service session. Android's cumulative step-counter sensor may reset after reboot and may be unavailable on some devices.
- Manual walk sessions and daily aggregates are persisted locally in Room.
- This starter is an implementation foundation, not a validated production fitness tracker. Test on multiple devices and Android versions before release.
- Optional automatic mode currently starts a location foreground service when enabled. Android may restrict background starts and background location access; proper onboarding and background-location permission handling are required before public release.
- Session route coordinates are collected in memory in this starter; persistent route-to-session linking and route replay are marked for the next data-layer hardening pass.
- Optional cloud account and sync are not connected. The app is offline-first; add a backend and authenticated sync before enabling cross-device accounts.
- Calories are not presented as a measured value. Accurate calorie estimates need user profile inputs and a validated model.

## Privacy
Location is sensitive. Keep tracking opt-in, show an ongoing notification during active tracking, provide a clear stop action, and explain retention before publishing.
