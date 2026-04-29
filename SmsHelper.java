// SmsHelper.java
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/SmsHelper.java
//
// PURPOSE: Send SMS silently using Android SmsManager (no user interaction).
//
// ⚠️  IMPORTANT LIMITATIONS (Android 10+):
//     1. On Android 10+, only the DEFAULT SMS APP can use SmsManager.sendTextMessage()
//        silently. All other apps will throw an IllegalStateException.
//     2. WorkAround A: Use SmsManager.createForSubscriptionId() and handle the
//        RESULT_ERROR_GENERIC_FAILURE gracefully.
//     3. WorkAround B: Use the intent-based approach to pre-fill SMS app, but
//        that requires user to tap Send — not fully silent.
//     4. WorkAround C: Request the user to set your app as default SMS app via
//        Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT — Android allows this but
//        Play Store may flag it.
//
// For production emergency apps, approach C (become default SMS app temporarily)
// or use a server-side SMS API (Twilio/Fast2SMS) as the primary path.
//
// This module implements the silent SmsManager path with proper error handling.

package com.emergencyapp;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class SmsHelper extends ReactContextBaseJavaModule {

    private static final String TAG = "SmsHelper";
    private static final String SMS_SENT_ACTION     = "com.emergencyapp.SMS_SENT";
    private static final String SMS_DELIVERED_ACTION = "com.emergencyapp.SMS_DELIVERED";

    private final ReactApplicationContext reactContext;

    public SmsHelper(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
    }

    @NonNull
    @Override
    public String getName() {
        // This is how JavaScript accesses this module:
        // NativeModules.SmsHelper.sendEmergencySms(...)
        return "SmsHelper";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // sendEmergencySms()
    //
    // @param phoneNumber  E.164 format: "+919XXXXXXXXX"
    // @param message      SMS body text (max 160 chars per segment; longer auto-splits)
    // @param promise      Resolves with {sent: true} or rejects with error message
    // ──────────────────────────────────────────────────────────────────────────
    @ReactMethod
    public void sendEmergencySms(String phoneNumber, String message, Promise promise) {
        try {
            SmsManager smsManager = getSmsManager();

            // Split long messages into multipart SMS automatically
            ArrayList<String> parts = smsManager.divideMessage(message);
            int partCount = parts.size();

            Log.d(TAG, "Sending " + partCount + " SMS part(s) to " + phoneNumber);

            // Build PendingIntents for each part's sent/delivery confirmation
            ArrayList<PendingIntent> sentIntents     = new ArrayList<>();
            ArrayList<PendingIntent> deliveredIntents = new ArrayList<>();

            // Track how many parts have been confirmed
            AtomicInteger sentCount = new AtomicInteger(0);

            for (int i = 0; i < partCount; i++) {
                final int partIndex = i;
                final boolean isLastPart = (i == partCount - 1);

                // ── Sent receiver ─────────────────────────────────────────────
                String sentAction = SMS_SENT_ACTION + "_" + System.currentTimeMillis() + "_" + i;
                Intent sentIntent = new Intent(sentAction);
                int flags = PendingIntent.FLAG_ONE_SHOT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                PendingIntent sentPI = PendingIntent.getBroadcast(
                        reactContext, partIndex, sentIntent, flags);
                sentIntents.add(sentPI);

                // Register receiver for this part
                BroadcastReceiver sentReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int resultCode = getResultCode();
                        String errorMsg = getSmsResultMessage(resultCode);

                        Log.d(TAG, "SMS part " + partIndex + " sent result: " + resultCode + " - " + errorMsg);

                        if (resultCode == Activity.RESULT_OK) {
                            int confirmed = sentCount.incrementAndGet();
                            if (isLastPart && confirmed >= partCount) {
                                // All parts sent successfully
                                WritableMap result = Arguments.createMap();
                                result.putBoolean("sent", true);
                                result.putString("recipient", phoneNumber);
                                result.putInt("parts", partCount);
                                promise.resolve(result);
                                emitEvent("SmsSent", result);
                            }
                        } else {
                            // SMS failed
                            promise.reject("SMS_SEND_FAILED", "Part " + partIndex + " failed: " + errorMsg);
                            emitEvent("SmsFailed", createErrorMap(phoneNumber, errorMsg));
                        }

                        // Unregister this receiver
                        try { reactContext.unregisterReceiver(this); } catch (Exception ignored) {}
                    }
                };

                IntentFilter sentFilter = new IntentFilter(sentAction);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    reactContext.registerReceiver(sentReceiver, sentFilter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    reactContext.registerReceiver(sentReceiver, sentFilter);
                }

                // ── Delivery receiver ─────────────────────────────────────────
                String deliveredAction = SMS_DELIVERED_ACTION + "_" + System.currentTimeMillis() + "_" + i;
                Intent deliveredIntent = new Intent(deliveredAction);
                PendingIntent deliveredPI = PendingIntent.getBroadcast(
                        reactContext, partIndex + 1000, deliveredIntent, flags);
                deliveredIntents.add(deliveredPI);

                BroadcastReceiver deliveredReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int resultCode = getResultCode();
                        WritableMap result = Arguments.createMap();
                        result.putString("recipient", phoneNumber);
                        result.putBoolean("delivered", resultCode == Activity.RESULT_OK);
                        result.putInt("part", partIndex);
                        emitEvent("SmsDelivered", result);
                        try { reactContext.unregisterReceiver(this); } catch (Exception ignored) {}
                    }
                };

                IntentFilter deliveredFilter = new IntentFilter(deliveredAction);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    reactContext.registerReceiver(deliveredReceiver, deliveredFilter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    reactContext.registerReceiver(deliveredReceiver, deliveredFilter);
                }
            }

            // ── Actually send ──────────────────────────────────────────────────
            if (partCount == 1) {
                // Single-part SMS — simple API
                smsManager.sendTextMessage(
                        phoneNumber,
                        null,           // scAddress (null = default SIM)
                        message,
                        sentIntents.get(0),
                        deliveredIntents.get(0)
                );
            } else {
                // Multi-part SMS
                smsManager.sendMultipartTextMessage(
                        phoneNumber,
                        null,
                        parts,
                        sentIntents,
                        deliveredIntents
                );
            }

        } catch (SecurityException e) {
            // Thrown on Android 10+ when app is not the default SMS app
            Log.e(TAG, "SMS SecurityException - not default SMS app: " + e.getMessage());
            promise.reject("SMS_PERMISSION_ERROR",
                    "Cannot send SMS silently: this app must be set as the default SMS app on Android 10+. " +
                    "Error: " + e.getMessage());

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid phone number or message: " + e.getMessage());
            promise.reject("SMS_INVALID_ARGS", "Invalid phone number or message: " + e.getMessage());

        } catch (Exception e) {
            Log.e(TAG, "SMS send failed: " + e.getMessage());
            promise.reject("SMS_ERROR", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // sendToMultipleContacts()
    // Sends SMS to an array of phone numbers
    // ──────────────────────────────────────────────────────────────────────────
    @ReactMethod
    public void sendToMultipleContacts(ReadableArray phoneNumbers, String message, Promise promise) {
        if (phoneNumbers == null || phoneNumbers.size() == 0) {
            promise.reject("NO_CONTACTS", "Phone numbers array is empty");
            return;
        }

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);
        int total = phoneNumbers.size();

        for (int i = 0; i < total; i++) {
            final String number = phoneNumbers.getString(i);
            if (number == null || number.isEmpty()) continue;

            // Use a simple inline promise-like callback per number
            sendSingleSms(number, message, new SmsCallback() {
                @Override
                public void onSuccess() {
                    int successes = successCount.incrementAndGet();
                    checkDone(successes, failCount.get(), total, promise);
                }
                @Override
                public void onFailed(String error) {
                    int failures = failCount.incrementAndGet();
                    Log.w(TAG, "Failed to send to " + number + ": " + error);
                    checkDone(successCount.get(), failures, total, promise);
                }
                private void checkDone(int s, int f, int t, Promise p) {
                    if (s + f >= t) {
                        WritableMap result = Arguments.createMap();
                        result.putInt("sent", s);
                        result.putInt("failed", f);
                        result.putInt("total", t);
                        if (s > 0) p.resolve(result);
                        else p.reject("ALL_FAILED", "All " + t + " SMS attempts failed");
                    }
                }
            });
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper: get the correct SmsManager (handles dual-SIM, Android 12+)
    // ──────────────────────────────────────────────────────────────────────────
    @SuppressWarnings("deprecation")
    private SmsManager getSmsManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ – createForDefaultSmsApp returns the manager
            // associated with the default SIM for sending
            return reactContext.getSystemService(SmsManager.class);
        } else {
            return SmsManager.getDefault();
        }
    }

    private void sendSingleSms(String phoneNumber, String message, SmsCallback callback) {
        try {
            SmsManager smsManager = getSmsManager();
            ArrayList<String> parts = smsManager.divideMessage(message);
            ArrayList<PendingIntent> sentIntents = new ArrayList<>();

            String sentAction = SMS_SENT_ACTION + "_bulk_" + phoneNumber.hashCode() + "_" + System.currentTimeMillis();
            Intent sentIntent = new Intent(sentAction);
            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent sentPI = PendingIntent.getBroadcast(reactContext, 0, sentIntent, flags);
            sentIntents.add(sentPI);

            BroadcastReceiver sentReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (getResultCode() == Activity.RESULT_OK) callback.onSuccess();
                    else callback.onFailed(getSmsResultMessage(getResultCode()));
                    try { reactContext.unregisterReceiver(this); } catch (Exception ignored) {}
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                reactContext.registerReceiver(sentReceiver, new IntentFilter(sentAction), Context.RECEIVER_NOT_EXPORTED);
            } else {
                reactContext.registerReceiver(sentReceiver, new IntentFilter(sentAction));
            }

            if (parts.size() == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, sentPI, null);
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null);
            }
        } catch (Exception e) {
            callback.onFailed(e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Emit events to JS (subscribe with NativeEventEmitter in React Native)
    // ──────────────────────────────────────────────────────────────────────────
    private void emitEvent(String eventName, WritableMap params) {
        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
        } catch (Exception e) {
            Log.e(TAG, "Event emission failed: " + e.getMessage());
        }
    }

    private WritableMap createErrorMap(String recipient, String error) {
        WritableMap map = Arguments.createMap();
        map.putString("recipient", recipient);
        map.putString("error", error);
        return map;
    }

    private String getSmsResultMessage(int resultCode) {
        switch (resultCode) {
            case Activity.RESULT_OK:                      return "Success";
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "Generic failure (check SIM/network)";
            case SmsManager.RESULT_ERROR_NO_SERVICE:      return "No cellular service";
            case SmsManager.RESULT_ERROR_NULL_PDU:        return "Null PDU error";
            case SmsManager.RESULT_ERROR_RADIO_OFF:       return "Radio/airplane mode is on";
            default:                                       return "Unknown error code: " + resultCode;
        }
    }

    // Callback interface for internal use
    private interface SmsCallback {
        void onSuccess();
        void onFailed(String error);
    }
}
