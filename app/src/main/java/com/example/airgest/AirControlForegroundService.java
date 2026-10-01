package com.example.airgest;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

/**
 * Keeps Air Control alive after the user leaves the app.
 *
 * Android only lets an app use the camera while it is visible, OR while it runs a foreground service
 * of type "camera" that was started while the app was visible. That is exactly what this is: the
 * user taps START AIR CONTROL in the app, we start this service with the mandatory persistent
 * notification ("Air Control Active"), and the camera keeps running when the user goes Home.
 * Android shows the camera-in-use indicator while it does - this is by design and can not be hidden.
 *
 * Data flow (all on-device):
 *   CameraX -> MediaPipe HandLandmarker -> HandFrame -> GestureDetector (state machine)
 *           -> ActionManager -> AirControlAccessibilityService.dispatchGesture() -> the app on screen
 */
public class AirControlForegroundService extends LifecycleService
        implements GestureDetector.Listener, HandTrackingManager.Listener {

    public static final String ACTION_START = "com.example.airgest.action.START";
    public static final String ACTION_STOP = "com.example.airgest.action.STOP";

    private static final String CHANNEL_ID = "air_control_active";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        Intent i = new Intent(context, AirControlForegroundService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, AirControlForegroundService.class).setAction(ACTION_STOP));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SettingsManager settings;
    private ActionManager actionManager;
    private GestureDetector detector;
    private HandTrackingManager tracking;
    private KeyguardManager keyguard;

    private boolean receiversRegistered;
    private boolean settingsListenerRegistered;
    private AirControlAccessibilityService lastSvc;
    private boolean lastNotifiedConnected = true;
    private String lastGestureLabel = "NONE";

    // fps counter
    private long fpsWindowStart;
    private int fpsFrames;

    // =========================================================================================
    // lifecycle
    // =========================================================================================
    @Override
    public void onCreate() {
        super.onCreate();
        settings = new SettingsManager(this);
        actionManager = new ActionManager(this, settings);
        detector = new GestureDetector(this);
        detector.setConfig(settings.toConfig());
        tracking = new HandTrackingManager(this, this, this);
        keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        createChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (!running) {
            if (!begin()) {
                shutdown();
                return START_NOT_STICKY;
            }
        }
        return START_NOT_STICKY;
    }

    /** Starts foreground mode and tracking. Returns false (and records why) if it can not. */
    private boolean begin() {
        AirControlState.resetRuntime();
        AirControlState.lastError = "";
        try {
            Notification n = buildNotification(getString(R.string.notif_text_running));
            if (Build.VERSION.SDK_INT >= 30) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            // e.g. camera permission missing, or Android refused a background start
            AirControlState.lastError = "Foreground service could not start: " + e.getMessage();
            return false;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            AirControlState.lastError = "Camera permission denied.";
            return false;
        }

        running = true;
        AirControlState.controllerActive = true;
        AirControlState.paused = false;

        registerReceivers();
        settings.registerListener(prefsListener);
        settingsListenerRegistered = true;

        if (!tracking.start(settings.getFps())) {
            // onTrackingError already stored the message
            return false;
        }
        main.postDelayed(watchdog, 2000);
        return true;
    }

    private void shutdown() {
        running = false;
        AirControlState.controllerActive = false;
        AirControlState.paused = false;
        main.removeCallbacksAndMessages(null);

        if (settingsListenerRegistered) {
            settings.unregisterListener(prefsListener);
            settingsListenerRegistered = false;
        }
        unregisterReceivers();
        try { tracking.stop(); } catch (Exception ignored) { }
        try { detector.reset(); } catch (Exception ignored) { }
        try { actionManager.releaseAll(); } catch (Exception ignored) { }
        AirControlAccessibilityService svc = AirControlAccessibilityService.getInstance();
        if (svc != null && svc.getOverlay() != null) svc.getOverlay().destroy();
        lastSvc = null;
        AirControlState.resetRuntime();

        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) { }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running) shutdown();
        super.onDestroy();
    }

    // =========================================================================================
    // frames from the camera (background thread) -> main thread
    // =========================================================================================
    @Override
    public void onHandFrame(HandFrame frame) {
        main.post(() -> processFrame(frame));
    }

    @Override
    public void onTrackingError(String message) {
        AirControlState.lastError = message;
        main.post(() -> {
            if (running) updateNotification(message);
        });
    }

    private void processFrame(HandFrame f) {
        if (!running || AirControlState.paused) return;

        long now = SystemClock.uptimeMillis();
        if (fpsWindowStart == 0) fpsWindowStart = now;
        fpsFrames++;
        if (now - fpsWindowStart >= 1000) {
            AirControlState.fps = Math.round(fpsFrames * 1000f / (now - fpsWindowStart));
            fpsFrames = 0;
            fpsWindowStart = now;
        }

        syncOverlay();
        detector.onFrame(f);
        AirControlState.pose = detector.getPose().name();
        AirControlState.mode = detector.getMode().name();
    }

    // =========================================================================================
    // GestureDetector.Listener
    // =========================================================================================
    @Override
    public void onStateChanged(GestureState state) {
        AirControlState.gestureState = state.name();
    }

    @Override
    public void onModeChanged(GestureDetector.Mode mode) {
        AirControlState.mode = mode.name();
        if (mode == GestureDetector.Mode.SCROLL) {
            showHud(GestureType.NONE, "OPEN PALM", "SCROLL MODE: HAND UP / DOWN");
        } else if (mode == GestureDetector.Mode.ZOOM) {
            showHud(GestureType.NONE, "TWO FINGERS", "ZOOM MODE: FINGERS APART / TOGETHER");
        }
    }

    @Override
    public void onHandPresence(boolean present) {
        AirControlState.handDetected = present;
        if (!present) {
            AirControlState.pose = "NONE";
        }
    }

    @Override
    public void onCursorMoved(float nx, float ny) {
        Point s = screen();
        float px = nx * s.x, py = ny * s.y;
        AirControlState.indexXpx = Math.round(px);
        AirControlState.indexYpx = Math.round(py);
        OverlayManager o = overlay();
        if (o != null && settings.isCursorEnabled()) o.moveCursorPx(px, py);
    }

    @Override
    public void onGesture(GestureType type, float confidence, float nx, float ny) {
        ActionManager.Result r = actionManager.handleGesture(type, nx, ny);
        AirControlState.gesture = type.name();
        AirControlState.confidence = confidence;
        AirControlState.lastAction = r.label;
        lastGestureLabel = type.label;
        showHud(type, type.label, r.label);
    }

    @Override
    public void onDragStart(float nx, float ny) {
        ActionManager.Result r = actionManager.handleDragStart(nx, ny);
        AirControlState.gesture = GestureType.FIST.name();
        AirControlState.lastAction = r.label;
        showHud(GestureType.FIST, "FIST (HOLD)", r.label);
    }

    @Override
    public void onDragMove(float nx, float ny) {
        actionManager.handleDragMove(nx, ny);
    }

    @Override
    public void onDragEnd(float nx, float ny) {
        ActionManager.Result r = actionManager.handleDragEnd(nx, ny);
        AirControlState.lastAction = r.label;
        showHud(GestureType.FIST, "OPEN HAND", r.label);
    }

    // =========================================================================================
    // overlay helpers
    // =========================================================================================
    /** The overlay owned by the accessibility service, or null while it is not connected. */
    @Nullable
    private OverlayManager overlay() {
        AirControlAccessibilityService svc = AirControlAccessibilityService.getInstance();
        return svc != null ? svc.getOverlay() : null;
    }

    private Point screen() {
        AirControlAccessibilityService svc = AirControlAccessibilityService.getInstance();
        if (svc != null) return svc.getScreenSize();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new Point(dm.widthPixels, dm.heightPixels);
    }

    /** (Re)creates the cursor whenever the accessibility service (re)connects or settings change. */
    private void syncOverlay() {
        AirControlAccessibilityService svc = AirControlAccessibilityService.getInstance();
        if (svc == lastSvc) return;
        lastSvc = svc;
        if (svc != null && svc.getOverlay() != null) {
            if (settings.isCursorEnabled()) {
                svc.getOverlay().showCursor(settings.getCursorSizeDp());
            } else {
                svc.getOverlay().hideCursor();
            }
        }
    }

    private void showHud(GestureType type, String gestureLabel, String actionLabel) {
        if (!settings.isHudEnabled()) return;
        OverlayManager o = overlay();
        if (o == null) return;
        GestureDetector.Mode m = detector.getMode();
        String mode = m == GestureDetector.Mode.NONE ? "ACTIVE" : m.name() + " MODE";
        o.showHud("DETECTED", mode, gestureLabel, actionLabel);
    }

    // =========================================================================================
    // settings / screen / accessibility watchdog
    // =========================================================================================
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, key) -> main.post(() -> {
                if (!running) return;
                detector.setConfig(settings.toConfig());
                tracking.setFps(settings.getFps());
                lastSvc = null; // rebuild the cursor with the new size / visibility
            });

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!running || intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    // battery: no camera while the screen is off
                    AirControlState.paused = true;
                    tracking.pause();
                    detector.reset();
                    actionManager.releaseAll();
                    AirControlState.handDetected = false;
                    break;
                case Intent.ACTION_SCREEN_ON:
                    if (keyguard == null || !keyguard.isKeyguardLocked()) resumeTracking();
                    break;
                case Intent.ACTION_USER_PRESENT:
                    resumeTracking();
                    break;
                default:
                    break;
            }
        }
    };

    private void resumeTracking() {
        if (!AirControlState.paused) return;
        AirControlState.paused = false;
        fpsWindowStart = 0;
        fpsFrames = 0;
        tracking.resume();
    }

    private void registerReceivers() {
        if (receiversRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiversRegistered = true;
    }

    private void unregisterReceivers() {
        if (!receiversRegistered) return;
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        receiversRegistered = false;
    }

    /** Every 2 s: tell the user in the notification if the accessibility service went away. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            boolean connected = AirControlAccessibilityService.isConnected();
            if (connected != lastNotifiedConnected) {
                lastNotifiedConnected = connected;
                updateNotification(getString(connected ? R.string.notif_text_running : R.string.notif_text_no_accessibility));
                if (!connected) AirControlState.lastError = "Accessibility service disconnected.";
            }
            main.postDelayed(this, 2000);
        }
    };

    // =========================================================================================
    // notification
    // =========================================================================================
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_channel_desc));
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, AirControlForegroundService.class).setAction(ACTION_STOP), piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .addAction(0, getString(R.string.notif_action_stop), stop)
                .addAction(0, getString(R.string.notif_action_open), open)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception ignored) { }
    }
}
