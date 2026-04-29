// PowerButtonReceiver.java
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/PowerButtonReceiver.java
//
// PURPOSE: Detect rapid power button presses and trigger emergency.
//
// ⚠️  ANDROID LIMITATIONS (READ THIS):
//
//  Android does NOT allow apps to intercept the actual power button KeyEvent.
//  The KEYCODE_POWER is consumed by the system — apps receive it only if they
//  hold focus AND only in certain Android versions.
//
//  WHAT WE CAN DO:
//  Monitor SCREEN_ON / SCREEN_OFF broadcasts as a proxy for power button presses.
//  - Press power once → screen goes OFF (ACTION_SCREEN_OFF)
//  - Press power again → screen goes ON (ACTION_SCREEN_ON)
//  - Count rapid alternations within a time window
//
//  LIMITATION: This also triggers for other screen-off events (idle timeout,
//  charging, etc.). We use a timing window to filter unintentional triggers.
//
//  ALTERNATIVE APPROACH (Samsung/manufacturer-specific):
//  Some OEMs expose accessibility APIs for power-button shortcuts.
//  An AccessibilityService can listen for GLOBAL_ACTION_POWER_DIALOG but
//  cannot suppress the power menu — it can only react to it.

package com.emergencyapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class PowerButtonReceiver extends BroadcastReceiver {

    private static final String TAG = "PowerButtonReceiver";

    // ── Configuration ────────────────────────────────────────────────────────
    // Number of presses required to trigger emergency
    private static final int REQUIRED_PRESSES = 3;

    // Maximum time window for all presses (milliseconds)
    // User has 2 seconds to press 3 times
    private static final long PRESS_WINDOW_MS = 2000L;

    // Minimum interval between presses (filter debounce)
    private static final long MIN_PRESS_INTERVAL_MS = 100L;

    // ── State ────────────────────────────────────────────────────────────────
    // Use static fields because BroadcastReceiver is instantiated fresh each time
    private static int   pressCount     = 0;
    private static long  lastPressTime  = 0;
    private static long  firstPressTime = 0;
    private static Handler resetHandler = null;
    private static Runnable resetRunnable = null;

    // ════════════════════════════════════════════════════════════════════════
    // BROADCAST RECEIVE
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.v(TAG, "Received: " + action);

        switch (action) {
            case Intent.ACTION_SCREEN_OFF:
                // Screen went OFF → power button was pressed
                handleScreenOff(context);
                break;

            case Intent.ACTION_SCREEN_ON:
                // Screen came ON → power button was pressed again
                // Also triggered by fingerprint/face unlock, so we primarily
                // count SCREEN_OFF events (more reliable)
                handleScreenOn(context);
                break;

            case Intent.ACTION_USER_PRESENT:
                // User unlocked the device — reset counter to avoid accidental trigger
                Log.v(TAG, "User unlocked — resetting press count");
                resetPressCount();
                break;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRESS DETECTION LOGIC
    // ════════════════════════════════════════════════════════════════════════

    private void handleScreenOff(Context context) {
        long now = System.currentTimeMillis();

        // Debounce: ignore presses that come too fast (system bouncing)
        if (now - lastPressTime < MIN_PRESS_INTERVAL_MS) {
            Log.v(TAG, "Debounced press — ignoring");
            return;
        }

        // Check if we're within the time window
        if (pressCount == 0) {
            // First press — start the window
            firstPressTime = now;
            initResetHandler();
        } else if (now - firstPressTime > PRESS_WINDOW_MS) {
            // Window expired — reset and start fresh
            Log.d(TAG, "Window expired — resetting. Starting fresh press sequence.");
            resetPressCount();
            firstPressTime = now;
            initResetHandler();
        }

        pressCount++;
        lastPressTime = now;

        Log.d(TAG, "Screen OFF press " + pressCount + "/" + REQUIRED_PRESSES +
                   " (window: " + (now - firstPressTime) + "ms)");

        // Schedule auto-reset after window expires
        scheduleReset(context);

        // Check if we've hit the threshold
        if (pressCount >= REQUIRED_PRESSES) {
            Log.w(TAG, "🚨 TRIPLE POWER PRESS DETECTED — triggering emergency!");
            resetPressCount(); // Reset immediately to prevent double-trigger
            triggerEmergency(context);
        }
    }

    private void handleScreenOn(Context context) {
        // We track SCREEN_OFF as the primary signal.
        // SCREEN_ON is logged for debugging but not counted.
        Log.v(TAG, "Screen ON (not counted — tracking SCREEN_OFF presses)");
    }

    private void triggerEmergency(Context context) {
        Log.w(TAG, "Emergency triggered by power button!");

        // Load contacts from SharedPreferences (saved by JS when user configures app)
        String[] contacts = loadEmergencyContacts(context);

        Intent serviceIntent = new Intent(context, EmergencyService.class);
        serviceIntent.setAction(EmergencyService.ACTION_START);
        serviceIntent.putExtra(EmergencyService.EXTRA_CONTACTS, contacts);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private void initResetHandler() {
        if (resetHandler == null) {
            resetHandler = new Handler(Looper.getMainLooper());
        }
    }

    private void scheduleReset(Context context) {
        if (resetHandler == null) initResetHandler();

        // Cancel any pending reset
        if (resetRunnable != null) {
            resetHandler.removeCallbacks(resetRunnable);
        }

        // Schedule reset after window + buffer
        resetRunnable = () -> {
            Log.v(TAG, "Press window expired — resetting counter (was: " + pressCount + ")");
            resetPressCount();
        };

        resetHandler.postDelayed(resetRunnable, PRESS_WINDOW_MS + 200L);
    }

    private static void resetPressCount() {
        pressCount     = 0;
        firstPressTime = 0;
        lastPressTime  = 0;
    }

    private String[] loadEmergencyContacts(Context context) {
        // Load from SharedPreferences (JS saves these when user sets up the app)
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                "emergency_prefs", Context.MODE_PRIVATE);
        String contactsStr = prefs.getString("emergency_contacts", "");

        if (contactsStr.isEmpty()) {
            Log.w(TAG, "No emergency contacts found in prefs — using default");
            return new String[]{"+912222222222"}; // Replace with real default
        }

        return contactsStr.split(",");
    }
}


// ════════════════════════════════════════════════════════════════════════════
// BootReceiver.java
// Restart services after device reboot
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/BootReceiver.java
// ════════════════════════════════════════════════════════════════════════════

/*
package com.emergencyapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Log.d(TAG, "Boot completed — restoring emergency guard");

            // Re-check if voice detection was enabled before reboot
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    "emergency_prefs", Context.MODE_PRIVATE);
            boolean voiceEnabled = prefs.getBoolean("voice_detection_enabled", false);

            if (voiceEnabled) {
                Intent voiceIntent = new Intent(context, VoiceService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(voiceIntent);
                } else {
                    context.startService(voiceIntent);
                }
                Log.d(TAG, "VoiceService restarted after boot");
            }

            // RetryManager will resume any pending SMS retries
            // (they're persisted in SharedPreferences)
        }
    }
}
*/
