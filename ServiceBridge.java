// ServiceBridge.java
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/ServiceBridge.java
//
// PURPOSE: Native module that is the single entry point for React Native to
// start/stop/configure all emergency services.
//
// REGISTER THIS MODULE in MainApplication.java:
//   packages.add(new EmergencyPackage());
//
// USAGE FROM JAVASCRIPT:
//   import { NativeModules } from 'react-native';
//   const { ServiceBridge } = NativeModules;
//   ServiceBridge.startEmergencyService(['+91XXXXXXXXXX']);

package com.emergencyapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;

public class ServiceBridge extends ReactContextBaseJavaModule {

    private static final String TAG = "ServiceBridge";
    private static final String PREFS = "emergency_prefs";

    private final ReactApplicationContext reactContext;

    public ServiceBridge(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
    }

    @NonNull
    @Override
    public String getName() {
        return "ServiceBridge";
    }

    // ════════════════════════════════════════════════════════════════════════
    // EMERGENCY SERVICE
    // ════════════════════════════════════════════════════════════════════════

    @ReactMethod
    public void startEmergencyService(ReadableArray contacts, Promise promise) {
        try {
            String[] contactArray = readableArrayToStringArray(contacts);
            if (contactArray.length == 0) {
                promise.reject("NO_CONTACTS", "At least one emergency contact is required");
                return;
            }

            Intent intent = new Intent(reactContext, EmergencyService.class);
            intent.setAction(EmergencyService.ACTION_START);
            intent.putExtra(EmergencyService.EXTRA_CONTACTS, contactArray);

            startServiceCompat(intent);
            Log.d(TAG, "EmergencyService started with " + contactArray.length + " contacts");
            promise.resolve(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start EmergencyService: " + e.getMessage());
            promise.reject("START_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void stopEmergencyService(Promise promise) {
        try {
            Intent intent = new Intent(reactContext, EmergencyService.class);
            intent.setAction(EmergencyService.ACTION_STOP);
            reactContext.startService(intent);
            Log.d(TAG, "EmergencyService stop requested");
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("STOP_FAILED", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // VOICE SERVICE
    // ════════════════════════════════════════════════════════════════════════

    @ReactMethod
    public void startVoiceDetection(Promise promise) {
        try {
            Intent intent = new Intent(reactContext, VoiceService.class);
            startServiceCompat(intent);

            // Persist preference for BootReceiver
            getPrefs().edit().putBoolean("voice_detection_enabled", true).apply();

            Log.d(TAG, "VoiceService started");
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("VOICE_START_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void stopVoiceDetection(Promise promise) {
        try {
            reactContext.stopService(new Intent(reactContext, VoiceService.class));
            getPrefs().edit().putBoolean("voice_detection_enabled", false).apply();
            Log.d(TAG, "VoiceService stopped");
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("VOICE_STOP_FAILED", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // EMERGENCY CONTACTS CONFIGURATION
    // ════════════════════════════════════════════════════════════════════════

    @ReactMethod
    public void saveEmergencyContacts(ReadableArray contacts, Promise promise) {
        try {
            String[] contactArray = readableArrayToStringArray(contacts);
            String contactsStr = android.text.TextUtils.join(",", contactArray);

            getPrefs().edit().putString("emergency_contacts", contactsStr).apply();

            Log.d(TAG, "Saved " + contactArray.length + " emergency contacts");
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("SAVE_FAILED", e.getMessage());
        }
    }

    @ReactMethod
    public void getEmergencyContacts(Promise promise) {
        String contactsStr = getPrefs().getString("emergency_contacts", "");
        com.facebook.react.bridge.WritableArray array =
                com.facebook.react.bridge.Arguments.createArray();

        if (!contactsStr.isEmpty()) {
            for (String contact : contactsStr.split(",")) {
                array.pushString(contact.trim());
            }
        }
        promise.resolve(array);
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent);
        } else {
            reactContext.startService(intent);
        }
    }

    private String[] readableArrayToStringArray(ReadableArray array) {
        if (array == null) return new String[0];
        String[] result = new String[array.size()];
        for (int i = 0; i < array.size(); i++) {
            result[i] = array.getString(i);
        }
        return result;
    }

    private SharedPreferences getPrefs() {
        return reactContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }
}
