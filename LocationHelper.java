// LocationHelper.java
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/LocationHelper.java
//
// PURPOSE: Reliable GPS acquisition using FusedLocationProviderClient.
//   - First tries fast cached/last-known location
//   - Falls back to fresh high-accuracy GPS
//   - Returns to React Native via Promise
//   - Also usable as a plain Java helper from EmergencyService

package com.emergencyapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.OnTokenCanceledListener;

public class LocationHelper extends ReactContextBaseJavaModule {

    private static final String TAG = "LocationHelper";

    // Timeout for location acquisition (milliseconds)
    private static final long LOCATION_TIMEOUT_MS        = 15_000L;
    private static final long FRESH_LOCATION_TIMEOUT_MS  = 20_000L;

    // Maximum age for cached location (milliseconds)
    private static final long MAX_CACHE_AGE_MS = 30_000L; // 30 seconds

    private final ReactApplicationContext reactContext;
    private final FusedLocationProviderClient fusedClient;
    private LocationCallback realtimeCallback;

    public LocationHelper(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
        this.fusedClient  = LocationServices.getFusedLocationProviderClient(context);
    }

    @NonNull
    @Override
    public String getName() {
        return "LocationHelper";
    }

    // ════════════════════════════════════════════════════════════════════════
    // getLastKnownLocation()
    // Returns the most recent cached location immediately.
    // Fast but potentially stale.
    // ════════════════════════════════════════════════════════════════════════

    @ReactMethod
    public void getLastKnownLocation(Promise promise) {
        if (!hasLocationPermission()) {
            promise.reject("PERMISSION_DENIED", "Location permission not granted");
            return;
        }

        try {
            fusedClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            long ageMs = System.currentTimeMillis() - location.getTime();
                            Log.d(TAG, "Last known location: age=" + (ageMs/1000) + "s, " +
                                       "accuracy=" + location.getAccuracy() + "m");
                            promise.resolve(locationToWritableMap(location));
                        } else {
                            // No cached location — fall back to fresh
                            Log.w(TAG, "No last known location available");
                            getCurrentLocation(promise);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "getLastLocation failed: " + e.getMessage());
                        promise.reject("LOCATION_ERROR", e.getMessage());
                    });
        } catch (SecurityException e) {
            promise.reject("PERMISSION_DENIED", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // getCurrentLocation()
    // Forces a fresh GPS reading. Slower but accurate.
    // ════════════════════════════════════════════════════════════════════════

    @ReactMethod
    public void getCurrentLocation(Promise promise) {
        if (!hasLocationPermission()) {
            promise.reject("PERMISSION_DENIED", "Location permission not granted");
            return;
        }

        try {
            // Build current location request
            CancellationToken cancellationToken = new CancellationToken() {
                @Override
                public boolean isCancellationRequested() { return false; }

                @NonNull
                @Override
                public CancellationToken onCanceledRequested(@NonNull OnTokenCanceledListener listener) {
                    return this;
                }
            };

            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationToken)
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            Log.d(TAG, "Current location: accuracy=" + location.getAccuracy() + "m");
                            promise.resolve(locationToWritableMap(location));
                        } else {
                            Log.w(TAG, "getCurrentLocation returned null");
                            // Last resort: start real-time updates and take first result
                            getLocationWithUpdates(promise);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "getCurrentLocation failed: " + e.getMessage());
                        promise.reject("LOCATION_ERROR", e.getMessage());
                    });

        } catch (SecurityException e) {
            promise.reject("PERMISSION_DENIED", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // getBestAvailableLocation()
    // Smart strategy: try cache first, fall back to fresh if stale.
    // Recommended for emergency use.
    // ════════════════════════════════════════════════════════════════════════

    @ReactMethod
    public void getBestAvailableLocation(Promise promise) {
        if (!hasLocationPermission()) {
            promise.reject("PERMISSION_DENIED", "Location permission not granted");
            return;
        }

        try {
            fusedClient.getLastLocation()
                    .addOnSuccessListener(lastLocation -> {
                        if (lastLocation != null) {
                            long ageMs = System.currentTimeMillis() - lastLocation.getTime();

                            if (ageMs <= MAX_CACHE_AGE_MS) {
                                // Recent enough — use it immediately
                                Log.d(TAG, "Using cached location (age: " + ageMs/1000 + "s)");
                                promise.resolve(locationToWritableMap(lastLocation));
                            } else {
                                // Stale — get fresh location
                                Log.d(TAG, "Cached location stale (" + ageMs/1000 + "s) — fetching fresh");
                                getCurrentLocationWithFallback(lastLocation, promise);
                            }
                        } else {
                            Log.d(TAG, "No cached location — fetching fresh");
                            getCurrentLocation(promise);
                        }
                    })
                    .addOnFailureListener(e -> getCurrentLocation(promise));

        } catch (SecurityException e) {
            promise.reject("PERMISSION_DENIED", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // startRealtimeTracking()
    // Continuous location updates. Use during active emergency.
    // ════════════════════════════════════════════════════════════════════════

    @ReactMethod
    public void startRealtimeTracking(Promise promise) {
        if (!hasLocationPermission()) {
            promise.reject("PERMISSION_DENIED", "Location permission not granted");
            return;
        }

        stopRealtimeTracking(); // Stop any existing tracking

        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                5_000L  // 5-second update interval
        )
                .setMinUpdateIntervalMillis(2_000L)  // No faster than every 2s
                .setMaxUpdateDelayMillis(10_000L)    // Max batch delay
                .setWaitForAccurateLocation(false)   // Don't wait for perfect fix
                .build();

        realtimeCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location location = result.getLastLocation();
                if (location != null) {
                    Log.d(TAG, "Realtime update: " + location.getLatitude() + ", " +
                               location.getLongitude() + " ±" + location.getAccuracy() + "m");
                    // In full implementation, emit event to JS here
                    // emitLocationUpdate(location);
                }
            }
        };

        try {
            fusedClient.requestLocationUpdates(locationRequest, realtimeCallback,
                    Looper.getMainLooper());
            promise.resolve(true);
            Log.d(TAG, "Real-time location tracking started");
        } catch (SecurityException e) {
            promise.reject("PERMISSION_DENIED", e.getMessage());
        }
    }

    @ReactMethod
    public void stopRealtimeTracking() {
        if (realtimeCallback != null) {
            fusedClient.removeLocationUpdates(realtimeCallback);
            realtimeCallback = null;
            Log.d(TAG, "Real-time tracking stopped");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // JAVA-ONLY API (for use from EmergencyService without RN bridge)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Get location with a callback. Use this from EmergencyService directly.
     *
     * @param context  Application context
     * @param callback LocationResultCallback
     */
    public static void getLocationForService(ReactApplicationContext context, LocationResultCallback callback) {
        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(context);

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onError("Location permission not granted");
            return;
        }

        client.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        long ageMs = System.currentTimeMillis() - location.getTime();
                        if (ageMs < 60_000) { // Less than 1 minute old
                            callback.onSuccess(location);
                            return;
                        }
                    }

                    // Get fresh
                    CancellationToken token = new CancellationToken() {
                        @Override public boolean isCancellationRequested() { return false; }
                        @NonNull @Override
                        public CancellationToken onCanceledRequested(@NonNull OnTokenCanceledListener l) { return this; }
                    };

                    try {
                        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token)
                                .addOnSuccessListener(fresh -> {
                                    if (fresh != null) callback.onSuccess(fresh);
                                    else callback.onError("Could not obtain location");
                                })
                                .addOnFailureListener(e -> callback.onError(e.getMessage()));
                    } catch (SecurityException e) {
                        callback.onError(e.getMessage());
                    }
                })
                .addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    public interface LocationResultCallback {
        void onSuccess(Location location);
        void onError(String error);
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private void getCurrentLocationWithFallback(Location fallback, Promise promise) {
        CancellationToken token = new CancellationToken() {
            @Override public boolean isCancellationRequested() { return false; }
            @NonNull @Override
            public CancellationToken onCanceledRequested(@NonNull OnTokenCanceledListener l) { return this; }
        };

        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token)
                    .addOnSuccessListener(location -> {
                        if (location != null) {
                            promise.resolve(locationToWritableMap(location));
                        } else {
                            // Return stale cached location with a warning
                            WritableMap map = locationToWritableMap(fallback);
                            map.putBoolean("isStale", true);
                            promise.resolve(map);
                        }
                    })
                    .addOnFailureListener(e -> {
                        // Return stale on failure
                        WritableMap map = locationToWritableMap(fallback);
                        map.putBoolean("isStale", true);
                        promise.resolve(map);
                    });
        } catch (SecurityException e) {
            promise.reject("PERMISSION_DENIED", e.getMessage());
        }
    }

    private void getLocationWithUpdates(Promise promise) {
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMaxUpdates(1) // We only want the first fix
                .setWaitForAccurateLocation(true)
                .build();

        LocationCallback singleUpdateCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location location = result.getLastLocation();
                fusedClient.removeLocationUpdates(this);
                if (location != null) {
                    promise.resolve(locationToWritableMap(location));
                } else {
                    promise.reject("LOCATION_ERROR", "Could not obtain GPS fix");
                }
            }
        };

        try {
            fusedClient.requestLocationUpdates(locationRequest, singleUpdateCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            promise.reject("PERMISSION_DENIED", e.getMessage());
        }
    }

    private WritableMap locationToWritableMap(Location location) {
        WritableMap map = Arguments.createMap();
        map.putDouble("latitude",  location.getLatitude());
        map.putDouble("longitude", location.getLongitude());
        map.putDouble("accuracy",  location.getAccuracy());
        map.putDouble("altitude",  location.getAltitude());
        map.putDouble("speed",     location.getSpeed());
        map.putDouble("bearing",   location.getBearing());
        map.putDouble("timestamp", location.getTime());
        map.putString("googleMapsUrl",
                "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude());
        map.putBoolean("isStale", false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            map.putDouble("verticalAccuracy", location.getVerticalAccuracyMeters());
            map.putDouble("speedAccuracy",    location.getSpeedAccuracyMetersPerSecond());
        }
        return map;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(reactContext,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}
