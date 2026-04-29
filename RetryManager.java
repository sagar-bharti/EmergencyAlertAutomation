// RetryManager.java
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/RetryManager.java
//
// PURPOSE: Robust SMS retry with exponential backoff and state persistence.
// Used internally by EmergencyService.

package com.emergencyapp;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SmsManager;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RetryManager {

    private static final String TAG = "RetryManager";

    // ── Retry configuration ──────────────────────────────────────────────────
    public static final int  MAX_ATTEMPTS          = 5;
    public static final long BASE_INTERVAL_MS      = 30_000L;  // 30 seconds
    public static final long MAX_INTERVAL_MS       = 300_000L; // 5 minutes cap
    public static final boolean USE_EXPONENTIAL_BACKOFF = false; // Linear for emergency
    // In emergencies, linear retry (every 30s) is preferred over exponential
    // because you want maximum attempts quickly. For non-emergency retry,
    // set USE_EXPONENTIAL_BACKOFF = true to avoid network flooding.

    // ── SharedPreferences keys (persist retry state across restarts) ─────────
    private static final String PREFS_NAME        = "retry_prefs";
    private static final String KEY_RETRY_COUNT   = "retry_count";
    private static final String KEY_PHONE         = "retry_phone";
    private static final String KEY_MESSAGE       = "retry_message";
    private static final String KEY_ACTIVE        = "retry_active";

    // ── AlarmManager action (used for exact-alarm retries on Android 12+) ───
    public static final String ALARM_RETRY_ACTION = "com.emergencyapp.RETRY_SMS_ALARM";

    private final Context context;
    private final Handler handler;
    private final ExecutorService executor;
    private final SharedPreferences prefs;

    private RetryCallback callback;
    private int currentAttempt = 0;
    private boolean cancelled = false;
    private Runnable pendingRetry;

    // ════════════════════════════════════════════════════════════════════════
    // INTERFACE
    // ════════════════════════════════════════════════════════════════════════

    public interface RetryCallback {
        boolean trySend(String phoneNumber, String message); // return true = success
        void onSuccess(String phoneNumber, int attemptNumber);
        void onAllAttemptsFailed(String phoneNumber, int totalAttempts);
        void onStatusUpdate(String message, int attempt, int maxAttempts);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ════════════════════════════════════════════════════════════════════════

    public RetryManager(Context context) {
        this.context  = context.getApplicationContext();
        this.handler  = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
        this.prefs    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void setCallback(RetryCallback callback) {
        this.callback = callback;
    }

    // ════════════════════════════════════════════════════════════════════════
    // START RETRY SEQUENCE
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Begin SMS sending with automatic retry.
     *
     * @param phoneNumber Target phone number (E.164 format)
     * @param message     SMS body text
     */
    public void start(String phoneNumber, String message) {
        cancelled      = false;
        currentAttempt = 0;

        // Persist state so we can resume after process restart
        persistRetryState(phoneNumber, message, 0, true);

        Log.d(TAG, "RetryManager starting for " + phoneNumber);
        attemptSend(phoneNumber, message);
    }

    /**
     * Resume a retry sequence (e.g. after service was restarted).
     * Call this in EmergencyService.onCreate() to resume interrupted retries.
     */
    public void resumeIfPending() {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return;

        String phone   = prefs.getString(KEY_PHONE, null);
        String message = prefs.getString(KEY_MESSAGE, null);
        int    count   = prefs.getInt(KEY_RETRY_COUNT, 0);

        if (phone == null || message == null) return;
        if (count >= MAX_ATTEMPTS) {
            clearPersistedState();
            return;
        }

        Log.d(TAG, "Resuming retry sequence at attempt " + count + " for " + phone);
        currentAttempt = count;
        attemptSend(phone, message);
    }

    // ════════════════════════════════════════════════════════════════════════
    // CORE RETRY LOOP
    // ════════════════════════════════════════════════════════════════════════

    private void attemptSend(String phoneNumber, String message) {
        if (cancelled) return;
        if (currentAttempt >= MAX_ATTEMPTS) {
            Log.e(TAG, "All " + MAX_ATTEMPTS + " attempts exhausted for " + phoneNumber);
            clearPersistedState();
            if (callback != null) callback.onAllAttemptsFailed(phoneNumber, currentAttempt);
            return;
        }

        currentAttempt++;
        Log.d(TAG, "Attempt " + currentAttempt + "/" + MAX_ATTEMPTS + " → " + phoneNumber);

        if (callback != null) {
            callback.onStatusUpdate(
                    "SMS attempt " + currentAttempt + "/" + MAX_ATTEMPTS,
                    currentAttempt,
                    MAX_ATTEMPTS
            );
        }

        // Run SMS send on background thread (telephony can block)
        executor.execute(() -> {
            boolean success = false;

            if (callback != null) {
                success = callback.trySend(phoneNumber, message);
            } else {
                // Fallback: direct SmsManager send
                success = directSmsSend(phoneNumber, message);
            }

            final boolean finalSuccess = success;
            handler.post(() -> {
                if (finalSuccess) {
                    Log.d(TAG, "✅ SMS sent on attempt " + currentAttempt);
                    clearPersistedState();
                    if (callback != null) callback.onSuccess(phoneNumber, currentAttempt);
                } else {
                    scheduleNextRetry(phoneNumber, message);
                }
            });
        });
    }

    private void scheduleNextRetry(String phoneNumber, String message) {
        if (cancelled || currentAttempt >= MAX_ATTEMPTS) return;

        long delay = calculateDelay(currentAttempt);

        Log.w(TAG, "SMS attempt " + currentAttempt + " failed — next retry in " +
                   (delay / 1000) + "s (" + currentAttempt + "/" + MAX_ATTEMPTS + " used)");

        persistRetryState(phoneNumber, message, currentAttempt, true);

        if (callback != null) {
            callback.onStatusUpdate(
                    "Retry " + currentAttempt + "/" + MAX_ATTEMPTS + " — next in " + (delay/1000) + "s",
                    currentAttempt,
                    MAX_ATTEMPTS
            );
        }

        // Use Handler for short delays, AlarmManager for long delays (>2 min)
        // AlarmManager survives process death; Handler does not
        if (delay <= 120_000L) {
            pendingRetry = () -> attemptSend(phoneNumber, message);
            handler.postDelayed(pendingRetry, delay);
        } else {
            scheduleAlarmRetry(phoneNumber, message, delay);
        }
    }

    /**
     * Calculate retry delay.
     * Linear: 30s, 30s, 30s, 30s, 30s
     * Exponential: 30s, 60s, 120s, 240s, 300s (capped)
     */
    private long calculateDelay(int attempt) {
        if (USE_EXPONENTIAL_BACKOFF) {
            long delay = BASE_INTERVAL_MS * (long) Math.pow(2, attempt - 1);
            return Math.min(delay, MAX_INTERVAL_MS);
        } else {
            return BASE_INTERVAL_MS; // Fixed 30-second intervals
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ALARMMANAGER-BASED RETRY (survives process death)
    // ════════════════════════════════════════════════════════════════════════

    private void scheduleAlarmRetry(String phoneNumber, String message, long delayMs) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent alarmIntent = new Intent(context, SmsRetryReceiver.class);
        alarmIntent.setAction(ALARM_RETRY_ACTION);
        alarmIntent.putExtra("phone", phoneNumber);
        alarmIntent.putExtra("message", message);
        alarmIntent.putExtra("attempt", currentAttempt);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(context, 999, alarmIntent, flags);

        long triggerAt = SystemClock.elapsedRealtime() + delayMs;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // setExactAndAllowWhileIdle fires even in Doze mode (critical for emergency)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }

        Log.d(TAG, "AlarmManager retry scheduled for " + (delayMs / 1000) + "s");
    }

    // ════════════════════════════════════════════════════════════════════════
    // CANCEL
    // ════════════════════════════════════════════════════════════════════════

    public void cancel() {
        cancelled = true;
        if (pendingRetry != null) handler.removeCallbacks(pendingRetry);
        clearPersistedState();
        Log.d(TAG, "RetryManager cancelled");
    }

    // ════════════════════════════════════════════════════════════════════════
    // STATE PERSISTENCE
    // ════════════════════════════════════════════════════════════════════════

    private void persistRetryState(String phone, String message, int attempt, boolean active) {
        prefs.edit()
             .putString(KEY_PHONE, phone)
             .putString(KEY_MESSAGE, message)
             .putInt(KEY_RETRY_COUNT, attempt)
             .putBoolean(KEY_ACTIVE, active)
             .apply();
    }

    private void clearPersistedState() {
        prefs.edit()
             .putBoolean(KEY_ACTIVE, false)
             .remove(KEY_PHONE)
             .remove(KEY_MESSAGE)
             .remove(KEY_RETRY_COUNT)
             .apply();
    }

    // ════════════════════════════════════════════════════════════════════════
    // DIRECT SMS (fallback when no callback set)
    // ════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("deprecation")
    private boolean directSmsSend(String phoneNumber, String message) {
        try {
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = context.getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }
            smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Direct SMS send failed: " + e.getMessage());
            return false;
        }
    }
}


// ════════════════════════════════════════════════════════════════════════════
// SmsRetryReceiver.java
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/SmsRetryReceiver.java
// This receives the AlarmManager broadcast and restarts the SMS send
// ════════════════════════════════════════════════════════════════════════════

/*
package com.emergencyapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class SmsRetryReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsRetryReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!RetryManager.ALARM_RETRY_ACTION.equals(intent.getAction())) return;

        String phone   = intent.getStringExtra("phone");
        String message = intent.getStringExtra("message");

        if (phone == null || message == null) return;

        Log.d(TAG, "Alarm-triggered SMS retry for: " + phone);

        // Restart EmergencyService which will use RetryManager internally
        Intent serviceIntent = new Intent(context, EmergencyService.class);
        serviceIntent.setAction(EmergencyService.ACTION_RETRY);
        serviceIntent.putExtra("phone", phone);
        serviceIntent.putExtra("message", message);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
*/
