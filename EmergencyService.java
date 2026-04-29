// EmergencyService.java
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/EmergencyService.java
//
// PURPOSE: Foreground service that coordinates the full emergency response:
//   1. Acquires GPS location
//   2. Sends SMS to all emergency contacts (with retry)
//   3. Places a phone call
//   4. Stays alive even when screen is off or app is minimized
//
// SERVICE LIFECYCLE:
//   START → onCreate() → onStartCommand() → [work runs] → stopSelf() or stopService()
//   The foreground notification MUST be posted in onCreate() or within 5 seconds.
//
// STARTING FROM REACT NATIVE:
//   See bottom of this file for the JS bridge code.

package com.emergencyapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.OnTokenCanceledListener;

import java.util.Arrays;
import java.util.List;

public class EmergencyService extends Service {

    private static final String TAG = "EmergencyService";

    // ── Intent actions ──────────────────────────────────────────────────────
    public static final String ACTION_START    = "START_EMERGENCY";
    public static final String ACTION_STOP     = "STOP_EMERGENCY";
    public static final String ACTION_RETRY    = "RETRY_SMS";
    public static final String EXTRA_CONTACTS  = "EMERGENCY_CONTACTS";   // String[]
    public static final String EXTRA_MESSAGE   = "CUSTOM_MESSAGE";       // optional

    // ── Notification ────────────────────────────────────────────────────────
    private static final String CHANNEL_ID       = "emergency_channel";
    private static final String CHANNEL_NAME     = "Emergency Alert";
    private static final int    NOTIFICATION_ID   = 1001;

    // ── Retry configuration ─────────────────────────────────────────────────
    private static final int  MAX_RETRY_ATTEMPTS   = 5;
    private static final long RETRY_INTERVAL_MS    = 30_000L; // 30 seconds

    // ── State ────────────────────────────────────────────────────────────────
    private Handler            retryHandler;
    private int                retryCount    = 0;
    private boolean            smsSentSuccessfully = false;
    private String[]           emergencyContacts;
    private String             smsMessage;
    private PowerManager.WakeLock wakeLock;

    private FusedLocationProviderClient fusedLocationClient;

    // ════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "EmergencyService onCreate");

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        retryHandler        = new Handler(Looper.getMainLooper());

        // MUST create notification channel before posting on Android 8+
        createNotificationChannel();

        // MUST call startForeground() within 5 seconds of onCreate() on Android 9+
        // to avoid ANR / ForegroundServiceStartNotAllowedException
        startForeground(NOTIFICATION_ID, buildNotification("Initializing emergency response..."));

        // Acquire a partial wake lock to keep CPU awake even if screen turns off
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":EmergencyWakeLock");
        wakeLock.acquire(10 * 60 * 1000L); // Auto-release after 10 minutes max
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // Service was restarted by Android after being killed — restart gracefully
            Log.w(TAG, "Service restarted with null intent — stopping self");
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: action=" + action);

        if (ACTION_STOP.equals(action)) {
            Log.d(TAG, "Stop action received");
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action) || action == null) {
            emergencyContacts = intent.getStringArrayExtra(EXTRA_CONTACTS);
            smsMessage        = intent.getStringExtra(EXTRA_MESSAGE);

            if (emergencyContacts == null || emergencyContacts.length == 0) {
                Log.e(TAG, "No emergency contacts provided");
                stopSelf();
                return START_NOT_STICKY;
            }

            updateNotification("🆘 Emergency activated — locating...");
            startEmergencySequence();
        }

        // START_STICKY: If killed, Android will restart us with a null intent
        // This is intentional for our use case (critical service)
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "EmergencyService onDestroy");
        cleanup();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // We are a started service, not a bound service
    }

    // ════════════════════════════════════════════════════════════════════════
    // EMERGENCY SEQUENCE
    // ════════════════════════════════════════════════════════════════════════

    private void startEmergencySequence() {
        Log.d(TAG, "Starting emergency sequence");
        retryCount = 0;

        // Step 1: Get location, then send SMS and call
        getLocationAndAlert();
    }

    private void getLocationAndAlert() {
        updateNotification("📍 Acquiring GPS location...");

        try {
            fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    new CancellationToken() {
                        @Override public boolean isCancellationRequested() { return false; }
                        @Override public CancellationToken onCanceledRequested(@NonNull OnTokenCanceledListener l) { return this; }
                    }
            ).addOnSuccessListener(location -> {
                if (location != null) {
                    Log.d(TAG, "Location: " + location.getLatitude() + ", " + location.getLongitude());
                    String mapLink = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                    String message = buildSmsMessage(mapLink, location.getAccuracy());
                    smsMessage = message;

                    updateNotification("📤 Sending emergency SMS...");
                    sendSmsToAllContacts(message);
                    placeEmergencyCall();
                } else {
                    Log.w(TAG, "Location null — sending SMS without location");
                    sendSmsToAllContacts(buildSmsMessage(null, 0));
                    placeEmergencyCall();
                }
            }).addOnFailureListener(e -> {
                Log.e(TAG, "Location failed: " + e.getMessage());
                sendSmsToAllContacts(buildSmsMessage(null, 0));
                placeEmergencyCall();
            });

        } catch (SecurityException e) {
            Log.e(TAG, "Location permission denied: " + e.getMessage());
            sendSmsToAllContacts(buildSmsMessage(null, 0));
            placeEmergencyCall();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SMS WITH RETRY LOGIC (see Step 6 for full retry implementation)
    // ════════════════════════════════════════════════════════════════════════

    private void sendSmsToAllContacts(String message) {
        for (String contact : emergencyContacts) {
            sendSmsWithRetry(contact, message);
        }
    }

    private void sendSmsWithRetry(String phoneNumber, String message) {
        Log.d(TAG, "Attempting SMS to " + phoneNumber + " (attempt " + (retryCount + 1) + ")");

        boolean sent = attemptSendSms(phoneNumber, message);

        if (sent) {
            smsSentSuccessfully = true;
            retryCount = 0;
            updateNotification("✅ Emergency alert sent to " + emergencyContacts.length + " contact(s)");
            Log.d(TAG, "SMS sent successfully to " + phoneNumber);
        } else {
            handleSmsFailure(phoneNumber, message);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean attemptSendSms(String phoneNumber, String message) {
        try {
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            if (message.length() > 160) {
                // Multi-part SMS
                smsManager.sendMultipartTextMessage(
                        phoneNumber, null,
                        smsManager.divideMessage(message),
                        null, null
                );
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            }
            return true;

        } catch (SecurityException e) {
            Log.e(TAG, "SMS SecurityException: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "SMS failed: " + e.getMessage());
            return false;
        }
    }

    // Full retry logic is in RetryManager (see Step 6)
    private void handleSmsFailure(String phoneNumber, String message) {
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCount++;
            long delay = RETRY_INTERVAL_MS;
            Log.w(TAG, "SMS failed — retry " + retryCount + "/" + MAX_RETRY_ATTEMPTS + " in " + (delay/1000) + "s");
            updateNotification("⚠️ SMS failed — retrying in " + (delay/1000) + "s (" + retryCount + "/" + MAX_RETRY_ATTEMPTS + ")");

            retryHandler.postDelayed(() -> {
                if (!smsSentSuccessfully) {
                    sendSmsWithRetry(phoneNumber, message);
                }
            }, delay);
        } else {
            Log.e(TAG, "All " + MAX_RETRY_ATTEMPTS + " SMS attempts failed for " + phoneNumber);
            updateNotification("❌ SMS failed after " + MAX_RETRY_ATTEMPTS + " attempts. Call 112 manually.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PHONE CALL
    // ════════════════════════════════════════════════════════════════════════

    private void placeEmergencyCall() {
        if (emergencyContacts == null || emergencyContacts.length == 0) return;

        String primaryContact = emergencyContacts[0];
        Log.d(TAG, "Placing call to: " + primaryContact);

        try {
            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(android.net.Uri.parse("tel:" + primaryContact));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(callIntent);
        } catch (SecurityException e) {
            Log.e(TAG, "CALL_PHONE permission not granted: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Call failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ════════════════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH  // Heads-up notification
            );
            channel.setDescription("Emergency alert status");
            channel.enableLights(true);
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setBypassDnd(true); // Bypass Do Not Disturb

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message) {
        // Tap notification → open app
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp, piFlags);

        // Stop service action in notification
        Intent stopIntent = new Intent(this, EmergencyService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPI = PendingIntent.getService(this, 1, stopIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🆘 Emergency Alert Active")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)          // Cannot be dismissed by user swipe
                .setAutoCancel(false)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPI)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lockscreen
                .build();
    }

    private void updateNotification(String message) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(message));
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private String buildSmsMessage(String mapLink, float accuracy) {
        String timestamp = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss",
                java.util.Locale.getDefault()).format(new java.util.Date());

        if (mapLink != null) {
            return "🆘 EMERGENCY ALERT 🆘\n\n" +
                   "I need immediate help!\n\n" +
                   "📍 Location: " + mapLink + "\n" +
                   "Accuracy: ±" + Math.round(accuracy) + "m\n" +
                   "⏰ Time: " + timestamp + "\n\n" +
                   "[Automated emergency message]";
        } else {
            return "🆘 EMERGENCY ALERT 🆘\n\n" +
                   "I need immediate help!\n" +
                   "Location unavailable.\n" +
                   "⏰ Time: " + timestamp + "\n\n" +
                   "[Automated emergency message]";
        }
    }

    private void cleanup() {
        if (retryHandler != null) {
            retryHandler.removeCallbacksAndMessages(null);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}

/*
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 HOW TO START/STOP FROM REACT NATIVE (JavaScript)

 Install react-native-background-actions or use our ServiceBridge module.
 Simplest approach using NativeModules:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// src/services/EmergencyServiceBridge.js

import { NativeModules, Platform } from 'react-native';
const { ServiceBridge } = NativeModules;

export const startEmergencyService = (contacts) => {
  if (Platform.OS === 'android') {
    ServiceBridge.startEmergencyService(contacts);
  }
};

export const stopEmergencyService = () => {
  if (Platform.OS === 'android') {
    ServiceBridge.stopEmergencyService();
  }
};

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 ServiceBridge.java (native module companion)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@ReactMethod
public void startEmergencyService(ReadableArray contacts) {
    Intent intent = new Intent(reactContext, EmergencyService.class);
    intent.setAction(EmergencyService.ACTION_START);

    String[] contactArray = new String[contacts.size()];
    for (int i = 0; i < contacts.size(); i++) {
        contactArray[i] = contacts.getString(i);
    }
    intent.putExtra(EmergencyService.EXTRA_CONTACTS, contactArray);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        reactContext.startForegroundService(intent);  // MUST use this on Android 8+
    } else {
        reactContext.startService(intent);
    }
}

@ReactMethod
public void stopEmergencyService() {
    Intent intent = new Intent(reactContext, EmergencyService.class);
    intent.setAction(EmergencyService.ACTION_STOP);
    reactContext.startService(intent);
}
*/
