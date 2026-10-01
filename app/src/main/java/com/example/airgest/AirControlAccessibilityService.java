package com.example.airgest;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The part of Air Control that actually touches other apps.
 *
 * It uses AccessibilityService.dispatchGesture() to inject swipes / taps / drags / pinch-zoom into
 * whatever app is on screen, and performGlobalAction() for Back/Home/Recents.
 *
 * Privacy: the service configuration (res/xml/accessibility_service_config.xml) does NOT request
 * "retrieve window content", so Air Control can not read what is on your screen. It only
 * listens for window-changed events to learn the foreground app's package name (used for
 * application profiles) and it never records or transmits anything.
 */
public class AirControlAccessibilityService extends AccessibilityService {

    private static volatile AirControlAccessibilityService instance;

    public static AirControlAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    /** True when the user has switched the service on in Android Settings (may not be connected yet). */
    public static boolean isEnabledInSettings(Context context) {
        try {
            int enabled = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) return false;
            String list = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(list)) return false;
            ComponentName me = new ComponentName(context, AirControlAccessibilityService.class);
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(list);
            while (splitter.hasNext()) {
                ComponentName cn = ComponentName.unflattenFromString(splitter.next());
                if (cn != null && cn.equals(me)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private OverlayManager overlay;
    private volatile String currentPackage = "";

    // one-shot gesture guard
    private boolean gestureBusy;
    private final Runnable clearBusy = () -> gestureBusy = false;

    // drag state
    private boolean dragActive, dragDispatching, dragEndRequested;
    private GestureDescription.StrokeDescription dragStroke;
    private float dragLastX, dragLastY, dragTargetX, dragTargetY;

    // =========================================================================================
    // lifecycle
    // =========================================================================================
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        overlay = new OverlayManager(this);
        instance = this;
        AirControlState.lastError = "";
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && isRealApp(pkg.toString())) {
                currentPackage = pkg.toString();
                AirControlState.foregroundPackage = currentPackage;
            }
        }
    }

    /** Ignore our own windows, the status bar/notification shade and keyboards. */
    private boolean isRealApp(String pkg) {
        return !pkg.equals(getPackageName())
                && !pkg.equals("com.android.systemui")
                && !pkg.toLowerCase().contains("inputmethod")
                && !pkg.toLowerCase().contains("keyboard");
    }

    @Override
    public void onInterrupt() {
        dragReset();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        dragReset();
        if (instance == this) instance = null;
        if (overlay != null) {
            overlay.destroy();
            overlay = null;
        }
        main.removeCallbacksAndMessages(null);
    }

    public OverlayManager getOverlay() {
        return overlay;
    }

    public String getCurrentPackage() {
        return currentPackage;
    }

    public Point getScreenSize() {
        return overlay != null ? overlay.getScreenSize() : new Point(1080, 2400);
    }

    // =========================================================================================
    // one-shot gestures
    // =========================================================================================
    private boolean dispatch(GestureDescription description, long expectedMs) {
        if (gestureBusy || dragActive) return false;
        gestureBusy = true;
        boolean ok;
        try {
            ok = dispatchGesture(description, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) {
                    main.removeCallbacks(clearBusy);
                    gestureBusy = false;
                }
                @Override public void onCancelled(GestureDescription d) {
                    main.removeCallbacks(clearBusy);
                    gestureBusy = false;
                }
            }, main);
        } catch (Exception e) {
            AirControlState.lastError = "dispatchGesture failed: " + e.getMessage();
            ok = false;
        }
        if (ok) {
            main.postDelayed(clearBusy, expectedMs + 700); // safety net if no callback arrives
        } else {
            gestureBusy = false;
        }
        return ok;
    }

    private float clampX(float x) {
        return Math.max(2f, Math.min(getScreenSize().x - 2f, x));
    }

    private float clampY(float y) {
        return Math.max(2f, Math.min(getScreenSize().y - 2f, y));
    }

    /** A finger swipe on the screen from (x1,y1) to (x2,y2). */
    public boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        Path p = new Path();
        p.moveTo(clampX(x1), clampY(y1));
        p.lineTo(clampX(x2), clampY(y2));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs))
                .build();
        return dispatch(g, durationMs);
    }

    public boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(clampX(x), clampY(y));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50))
                .build();
        return dispatch(g, 50);
    }

    public boolean doubleTap(float x, float y) {
        Path p1 = new Path();
        p1.moveTo(clampX(x), clampY(y));
        Path p2 = new Path();
        p2.moveTo(clampX(x), clampY(y));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p1, 0, 40))
                .addStroke(new GestureDescription.StrokeDescription(p2, 130, 40))
                .build();
        return dispatch(g, 170);
    }

    /**
     * Two-finger pinch centred on (cx,cy). Works in apps that implement pinch-to-zoom with a
     * normal two-pointer touch gesture (photo viewers, maps, browsers, PDF viewers ...).
     * Apps that do not support pinch-zoom simply ignore it - this is NOT universal.
     */
    public boolean zoom(boolean zoomIn, float cx, float cy) {
        Point s = getScreenSize();
        float minDim = Math.min(s.x, s.y);
        float near = minDim * 0.05f;
        float far = minDim * 0.22f;
        float a0 = zoomIn ? near : far;
        float a1 = zoomIn ? far : near;
        float x = Math.max(far + 4f, Math.min(s.x - far - 4f, cx));
        float y = clampY(cy);
        Path left = new Path();
        left.moveTo(x - a0, y);
        left.lineTo(x - a1, y);
        Path right = new Path();
        right.moveTo(x + a0, y);
        right.lineTo(x + a1, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(left, 0, 380))
                .addStroke(new GestureDescription.StrokeDescription(right, 0, 380))
                .build();
        return dispatch(g, 380);
    }

    public boolean global(int globalAction) {
        try {
            return performGlobalAction(globalAction);
        } catch (Exception e) {
            AirControlState.lastError = "Global action failed: " + e.getMessage();
            return false;
        }
    }

    public boolean mediaKey(int keyCode) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            return true;
        } catch (Exception e) {
            AirControlState.lastError = "Media key failed: " + e.getMessage();
            return false;
        }
    }

    // =========================================================================================
    // drag / hold  (a touch that stays down, built from continued strokes, API 26+)
    // =========================================================================================
    public boolean isDragging() {
        return dragActive;
    }

    public boolean dragStart(float x, float y) {
        if (gestureBusy || dragActive) return false;
        dragActive = true;
        dragEndRequested = false;
        dragStroke = null;
        dragLastX = dragTargetX = clampX(x);
        dragLastY = dragTargetY = clampY(y);
        dispatchDragSegment();
        return dragActive;
    }

    public void dragMove(float x, float y) {
        if (!dragActive) return;
        dragTargetX = clampX(x);
        dragTargetY = clampY(y);
    }

    public void dragEnd(float x, float y) {
        if (!dragActive) return;
        dragTargetX = clampX(x);
        dragTargetY = clampY(y);
        dragEndRequested = true;
        if (!dragDispatching) dispatchDragSegment();
    }

    private void dispatchDragSegment() {
        final float fx = dragLastX, fy = dragLastY, tx = dragTargetX, ty = dragTargetY;
        final boolean end = dragEndRequested;
        Path p = new Path();
        p.moveTo(fx, fy);
        if (tx != fx || ty != fy) p.lineTo(tx, ty);
        GestureDescription.StrokeDescription stroke;
        try {
            stroke = (dragStroke == null)
                    ? new GestureDescription.StrokeDescription(p, 0, 60, !end)
                    : dragStroke.continueStroke(p, 0, 60, !end);
        } catch (Exception e) {
            AirControlState.lastError = "Drag stroke failed: " + e.getMessage();
            dragReset();
            return;
        }
        dragStroke = stroke;
        dragDispatching = true;
        boolean ok;
        try {
            ok = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),
                    new GestureResultCallback() {
                        @Override public void onCompleted(GestureDescription d) {
                            dragDispatching = false;
                            dragLastX = tx;
                            dragLastY = ty;
                            if (end || !dragActive) {
                                dragReset();
                            } else {
                                dispatchDragSegment();
                            }
                        }
                        @Override public void onCancelled(GestureDescription d) {
                            dragReset();
                        }
                    }, main);
        } catch (Exception e) {
            ok = false;
        }
        if (!ok) dragReset();
    }

    private void dragReset() {
        dragActive = false;
        dragDispatching = false;
        dragEndRequested = false;
        dragStroke = null;
    }
}
