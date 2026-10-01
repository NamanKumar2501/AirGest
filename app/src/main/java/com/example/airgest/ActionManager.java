package com.example.airgest;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Point;
import android.view.KeyEvent;

/**
 * Maps a recognised gesture to an action (using the profile of the foreground app) and asks the
 * AccessibilityService to perform it. New actions are added by extending {@link GestureAction}
 * and the switch in {@link #perform}.
 */
public class ActionManager {

    /** Outcome shown in the overlay / debug screen. */
    public static class Result {
        public final GestureAction action;
        public final boolean executed;
        public final String label;

        Result(GestureAction action, boolean executed, String label) {
            this.action = action;
            this.executed = executed;
            this.label = label;
        }
    }

    private final SettingsManager settings;

    public ActionManager(Context context, SettingsManager settings) {
        this.settings = settings;
    }

    private AppProfile currentProfile(AirControlAccessibilityService svc) {
        return AppProfile.resolve(svc.getCurrentPackage());
    }

    public Result handleGesture(GestureType type, float nx, float ny) {
        AirControlAccessibilityService svc = AirControlAccessibilityService.getInstance();
        if (svc == null) {
            return new Result(GestureAction.NONE, false, "ACCESSIBILITY OFF");
        }
        GestureAction action = settings.getAction(currentProfile(svc), type);
        if (action == GestureAction.NONE) {
            return new Result(action, false, "NO ACTION (" + currentProfile(svc).displayName.toUpperCase() + ")");
        }
        if (action == GestureAction.DRAG) {
            return new Result(action, false, "DRAG");
        }
        boolean ok = perform(svc, action, nx, ny);
        return new Result(action, ok, ok ? action.overlayLabel : action.overlayLabel + " (BUSY)");
    }

    // ----- drag / hold: FIST is mapped like any other gesture ------------------------------------
    private boolean dragMapped(AirControlAccessibilityService svc) {
        return settings.getAction(currentProfile(svc), GestureType.FIST) == GestureAction.DRAG;
    }

    public Result handleDragStart(float nx, float ny) {
        AirControlAccessibilityService svc = AirControlAccessibilityService.getInstance();
        if (svc == null) return new Result(GestureAction.NONE, false, "ACCESSIBILITY OFF");
        if (!dragMapped(svc)) return new Result(GestureAction.NONE, false, "NO ACTION");
        Point s = svc.getScreenSize();
        boolean ok = svc.dragStart(nx * s.x, ny * s.y);
        if (ok && svc.getOverlay() != null) svc.getOverlay().setCursorActive(true);
        return new Result(GestureAction.DRAG, ok, ok ? "DRAG - HOLDING" : "DRAG (BUSY)");
    }

    public void handleDragMove(float nx, float ny) {
        AirControlAccessibilityService svc = AirControlAccessibilityService.getInstance();
        if (svc == null || !svc.isDragging()) return;
        Point s = svc.getScreenSize();
        svc.dragMove(nx * s.x, ny * s.y);
    }

    public Result handleDragEnd(float nx, float ny) {
        AirControlAccessibilityService svc = AirControlAccessibilityService.getInstance();
        if (svc == null) return new Result(GestureAction.NONE, false, "ACCESSIBILITY OFF");
        boolean wasDragging = svc.isDragging();
        Point s = svc.getScreenSize();
        svc.dragEnd(nx * s.x, ny * s.y);
        if (svc.getOverlay() != null) svc.getOverlay().setCursorActive(false);
        return new Result(GestureAction.DRAG, wasDragging, "DRAG - RELEASED");
    }

    /** Releases a drag that is still in progress (controller stopped). */
    public void releaseAll() {
        AirControlAccessibilityService svc = AirControlAccessibilityService.getInstance();
        if (svc != null && svc.isDragging()) {
            Point s = svc.getScreenSize();
            svc.dragEnd(s.x / 2f, s.y / 2f);
            if (svc.getOverlay() != null) svc.getOverlay().setCursorActive(false);
        }
    }

    // ----- the actual actions --------------------------------------------------------------------
    private boolean perform(AirControlAccessibilityService svc, GestureAction action, float nx, float ny) {
        Point s = svc.getScreenSize();
        final float w = s.x, h = s.y;
        final float px = nx * w, py = ny * h;
        boolean invert = settings.isInvertScroll();

        switch (action) {
            case NEXT:      // finger swipes right -> left, the way you flip to the next photo
                return svc.swipe(w * 0.85f, h * 0.45f, w * 0.15f, h * 0.45f, 220);
            case PREVIOUS:  // finger swipes left -> right
                return svc.swipe(w * 0.15f, h * 0.45f, w * 0.85f, h * 0.45f, 220);
            case NEXT_PAGE:
                return svc.swipe(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.25f, 300);
            case PREVIOUS_PAGE:
                return svc.swipe(w * 0.5f, h * 0.25f, w * 0.5f, h * 0.75f, 300);
            case CLICK:
                pulse(svc);
                return svc.tap(px, py);
            case DOUBLE_CLICK:
                pulse(svc);
                return svc.doubleTap(px, py);
            case SCROLL_UP:   // show content above: finger moves DOWN
                return invert ? swipeContentDown(svc, w, h) : swipeContentUp(svc, w, h);
            case SCROLL_DOWN: // show content below: finger moves UP
                return invert ? swipeContentUp(svc, w, h) : swipeContentDown(svc, w, h);
            case ZOOM_IN:
                return svc.zoom(true, px, py);
            case ZOOM_OUT:
                return svc.zoom(false, px, py);
            case MEDIA_NEXT:
                return svc.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            case MEDIA_PREVIOUS:
                return svc.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            case MEDIA_PLAY_PAUSE:
                return svc.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            case BACK:
                return svc.global(AccessibilityService.GLOBAL_ACTION_BACK);
            case HOME:
                return svc.global(AccessibilityService.GLOBAL_ACTION_HOME);
            case RECENTS:
                return svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS);
            default:
                return false;
        }
    }

    /** "Scroll up": the view moves towards its top, so the finger swipes downwards. */
    private boolean swipeContentUp(AirControlAccessibilityService svc, float w, float h) {
        return svc.swipe(w * 0.5f, h * 0.35f, w * 0.5f, h * 0.70f, 260);
    }

    private boolean swipeContentDown(AirControlAccessibilityService svc, float w, float h) {
        return svc.swipe(w * 0.5f, h * 0.70f, w * 0.5f, h * 0.35f, 260);
    }

    private void pulse(AirControlAccessibilityService svc) {
        if (svc.getOverlay() != null) svc.getOverlay().pulseCursor();
    }
}
