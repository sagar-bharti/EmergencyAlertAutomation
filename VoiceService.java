// VoiceService.java
// Place at: EmergencyApp/android/app/src/main/java/com/emergencyapp/VoiceService.java
//
// PURPOSE: Continuously listen for the keyword "help" and trigger EmergencyService.
//
// ⚠️  CRITICAL LIMITATIONS YOU MUST UNDERSTAND:
//
//  1. OFFLINE KEYWORD DETECTION:
//     Android's built-in SpeechRecognizer (RecognizerIntent) uses Google's cloud
//     server. It IS NOT offline and has session limits (~60 seconds per session).
//     True offline wake-word detection requires:
//       a) Porcupine SDK (by Picovoice) — best option, has "hey porcupine" free
//          or custom wake words (paid). Works offline, very low CPU.
//       b) CMU PocketSphinx — free & offline but lower accuracy
//       c) Vosk — free, open source, reasonable accuracy
//     For a production app, use Porcupine. This implementation uses
//     Android's SpeechRecognizer as the functional baseline.
//
//  2. BACKGROUND MICROPHONE:
//     - Android 9+: Must be a Foreground Service with microphone type
//     - Android 12+: Cannot use microphone in background without foreground service
//     - The foreground notification MUST be visible to the user
//     - This is a hard Android requirement. There is no workaround.
//
//  3. BATTERY IMPACT:
//     Continuous mic listening is battery-intensive (~5-15% extra drain/hour).
//     Recommend: Only start VoiceService when user explicitly enables it.

package com.emergencyapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class VoiceService extends Service {

    private static final String TAG = "VoiceService";

    // ── Configuration ────────────────────────────────────────────────────────
    // Keywords that trigger the emergency (case-insensitive, partial match)
    private static final List<String> TRIGGER_KEYWORDS = Arrays.asList(
            "help help",
            "help me",
            "emergency",
            "somebody help",
            "call police"
    );

    private static final long RESTART_DELAY_MS     = 500L;   // Delay between recognition sessions
    private static final long COOLDOWN_MS           = 30_000L; // 30s cooldown after trigger
    private static final int  CHANNEL_ID_INT        = 1002;
    private static final String VOICE_CHANNEL_ID    = "voice_channel";

    // Emergency contacts (in real app, load from SharedPreferences/DB)
    private static final String[] DEFAULT_CONTACTS = {"+919XXXXXXXXX"};

    // ── State ────────────────────────────────────────────────────────────────
    private SpeechRecognizer speechRecognizer;
    private Handler          restartHandler;
    private boolean          isListening       = false;
    private boolean          emergencyTriggered = false;
    private long             lastTriggerTime   = 0;

    // ════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "VoiceService onCreate");

        createVoiceNotificationChannel();
        startForeground(CHANNEL_ID_INT, buildVoiceNotification("👂 Listening for emergency keywords..."));

        restartHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "VoiceService starting speech recognition");

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            initializeSpeechRecognizer();
            startListening();
        } else {
            Log.e(TAG, "Speech recognition not available on this device");
            stopSelf();
        }

        return START_STICKY; // Restart if killed
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "VoiceService onDestroy");
        stopListening();
        if (restartHandler != null) restartHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ════════════════════════════════════════════════════════════════════════
    // SPEECH RECOGNIZER
    // ════════════════════════════════════════════════════════════════════════

    private void initializeSpeechRecognizer() {
        // SpeechRecognizer must be created on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(this::initializeSpeechRecognizer);
            return;
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                Log.v(TAG, "Ready for speech");
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null) {
                    processResults(matches);
                }
                // Restart listening automatically after results
                scheduleRestart();
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                // Check partial results for faster trigger response
                ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null) {
                    processResults(partial);
                }
            }

            @Override
            public void onError(int error) {
                isListening = false;
                String errorMsg = getSpeechErrorMessage(error);
                Log.w(TAG, "Speech recognition error: " + errorMsg + " (" + error + ")");

                // Schedule restart for recoverable errors
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        scheduleRestart();
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        Log.e(TAG, "Non-recoverable error — stopping VoiceService");
                        stopSelf();
                        break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        // Google ASR requires internet — use offline engine instead
                        Log.w(TAG, "Network error — recognition requires internet. Consider Porcupine for offline.");
                        scheduleRestart(5000); // Longer delay on network error
                        break;
                    default:
                        scheduleRestart(2000);
                }
            }

            @Override
            public void onBeginningOfSpeech() { Log.v(TAG, "Speech began"); }

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                Log.v(TAG, "Speech ended");
            }

            // Unused but required by interface
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startListening() {
        if (isListening) return;

        Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);

        // Keep listening even in quiet environment
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L);

        try {
            speechRecognizer.startListening(recognizerIntent);
            isListening = true;
            Log.d(TAG, "Started listening");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start listening: " + e.getMessage());
            scheduleRestart(2000);
        }
    }

    private void stopListening() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recognizer: " + e.getMessage());
            }
            speechRecognizer = null;
        }
        isListening = false;
    }

    private void scheduleRestart() {
        scheduleRestart(RESTART_DELAY_MS);
    }

    private void scheduleRestart(long delayMs) {
        restartHandler.postDelayed(() -> {
            if (speechRecognizer == null) initializeSpeechRecognizer();
            startListening();
        }, delayMs);
    }

    // ════════════════════════════════════════════════════════════════════════
    // KEYWORD DETECTION
    // ════════════════════════════════════════════════════════════════════════

    private void processResults(List<String> results) {
        // Check cooldown (avoid multiple triggers)
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active — ignoring recognition results");
            return;
        }

        for (String result : results) {
            String normalized = result.toLowerCase(Locale.getDefault()).trim();
            Log.d(TAG, "Recognized: \"" + normalized + "\"");

            for (String keyword : TRIGGER_KEYWORDS) {
                if (normalized.contains(keyword)) {
                    Log.w(TAG, "🚨 TRIGGER KEYWORD DETECTED: \"" + keyword + "\" in \"" + normalized + "\"");
                    onKeywordDetected(keyword, normalized);
                    return; // Only trigger once per recognition session
                }
            }
        }
    }

    private void onKeywordDetected(String keyword, String fullText) {
        lastTriggerTime = System.currentTimeMillis();
        updateVoiceNotification("🚨 Keyword \"" + keyword + "\" detected! Starting emergency...");

        Log.w(TAG, "EMERGENCY TRIGGERED by voice keyword: " + keyword);

        // Start EmergencyService
        Intent emergencyIntent = new Intent(this, EmergencyService.class);
        emergencyIntent.setAction(EmergencyService.ACTION_START);
        emergencyIntent.putExtra(EmergencyService.EXTRA_CONTACTS, DEFAULT_CONTACTS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(emergencyIntent);
        } else {
            startService(emergencyIntent);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NOTIFICATION
    // ════════════════════════════════════════════════════════════════════════

    private void createVoiceNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    VOICE_CHANNEL_ID, "Voice Detection", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Emergency keyword detection");
            channel.setSound(null, null); // Silent
            channel.enableVibration(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildVoiceNotification(String text) {
        return new NotificationCompat.Builder(this, VOICE_CHANNEL_ID)
                .setContentTitle("Emergency Guard Active")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide on lockscreen
                .build();
    }

    private void updateVoiceNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(CHANNEL_ID_INT, buildVoiceNotification(text));
    }

    private String getSpeechErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:                  return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:                 return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:               return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:       return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:              return "No recognition match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:       return "Recognizer busy";
            case SpeechRecognizer.ERROR_SERVER:                return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:        return "No speech input";
            default:                                           return "Unknown error";
        }
    }
}
