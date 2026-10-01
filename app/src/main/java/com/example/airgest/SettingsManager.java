package com.example.airgest;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings, stored locally in SharedPreferences. */
public class SettingsManager {

    public static final String PREFS_NAME = "air_control_prefs";

    // keys
    public static final String KEY_FPS = "tracking_fps";
    public static final String KEY_SENSITIVITY = "sensitivity";
    public static final String KEY_PINCH_THRESHOLD = "pinch_threshold";
    public static final String KEY_SWIPE_DISTANCE = "swipe_distance";
    public static final String KEY_SWIPE_SPEED = "swipe_speed";
    public static final String KEY_SMOOTHING = "cursor_smoothing";
    public static final String KEY_COOLDOWN = "cooldown_ms";
    public static final String KEY_CURSOR_ENABLED = "cursor_enabled";
    public static final String KEY_CURSOR_SIZE = "cursor_size_dp";
    public static final String KEY_HUD_ENABLED = "hud_enabled";
    public static final String KEY_INVERT_SCROLL = "invert_scroll";
    public static final String KEY_BOOT_PROMPT = "boot_prompt";
    private static final String KEY_MAP_PREFIX = "map.";

    // defaults
    public static final int DEF_FPS = 30;
    public static final float DEF_SENSITIVITY = 1.8f;
    public static final float DEF_PINCH_THRESHOLD = 0.32f;
    public static final float DEF_SWIPE_DISTANCE = 0.28f;
    public static final float DEF_SWIPE_SPEED = 0.9f;
    public static final float DEF_SMOOTHING = 0.55f;
    public static final int DEF_COOLDOWN = 800;
    public static final int DEF_CURSOR_SIZE = 28;

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs.registerOnSharedPreferenceChangeListener(l);
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        prefs.unregisterOnSharedPreferenceChangeListener(l);
    }

    // ----- tracking -----------------------------------------------------------------------------
    public int getFps() {
        int v = prefs.getInt(KEY_FPS, DEF_FPS);
        return (v == 30 || v == 24 || v == 15) ? v : DEF_FPS;
    }
    public void setFps(int fps) { prefs.edit().putInt(KEY_FPS, fps).apply(); }

    // ----- calibration ------------------------------------------------------------------------
    public float getSensitivity() { return prefs.getFloat(KEY_SENSITIVITY, DEF_SENSITIVITY); }
    public void setSensitivity(float v) { prefs.edit().putFloat(KEY_SENSITIVITY, v).apply(); }

    public float getPinchThreshold() { return prefs.getFloat(KEY_PINCH_THRESHOLD, DEF_PINCH_THRESHOLD); }
    public void setPinchThreshold(float v) { prefs.edit().putFloat(KEY_PINCH_THRESHOLD, v).apply(); }

    public float getSwipeDistance() { return prefs.getFloat(KEY_SWIPE_DISTANCE, DEF_SWIPE_DISTANCE); }
    public void setSwipeDistance(float v) { prefs.edit().putFloat(KEY_SWIPE_DISTANCE, v).apply(); }

    public float getSwipeSpeed() { return prefs.getFloat(KEY_SWIPE_SPEED, DEF_SWIPE_SPEED); }
    public void setSwipeSpeed(float v) { prefs.edit().putFloat(KEY_SWIPE_SPEED, v).apply(); }

    public float getSmoothing() { return prefs.getFloat(KEY_SMOOTHING, DEF_SMOOTHING); }
    public void setSmoothing(float v) { prefs.edit().putFloat(KEY_SMOOTHING, v).apply(); }

    public int getCooldownMs() { return prefs.getInt(KEY_COOLDOWN, DEF_COOLDOWN); }
    public void setCooldownMs(int v) { prefs.edit().putInt(KEY_COOLDOWN, v).apply(); }

    /** "RESET TO DEFAULT" on the calibration screen. */
    public void resetCalibration() {
        prefs.edit()
                .remove(KEY_SENSITIVITY).remove(KEY_PINCH_THRESHOLD).remove(KEY_SWIPE_DISTANCE)
                .remove(KEY_SWIPE_SPEED).remove(KEY_SMOOTHING).remove(KEY_COOLDOWN)
                .apply();
    }

    // ----- cursor / overlay ---------------------------------------------------------------------
    public boolean isCursorEnabled() { return prefs.getBoolean(KEY_CURSOR_ENABLED, true); }
    public void setCursorEnabled(boolean v) { prefs.edit().putBoolean(KEY_CURSOR_ENABLED, v).apply(); }

    public int getCursorSizeDp() { return prefs.getInt(KEY_CURSOR_SIZE, DEF_CURSOR_SIZE); }
    public void setCursorSizeDp(int v) { prefs.edit().putInt(KEY_CURSOR_SIZE, v).apply(); }

    public boolean isHudEnabled() { return prefs.getBoolean(KEY_HUD_ENABLED, true); }
    public void setHudEnabled(boolean v) { prefs.edit().putBoolean(KEY_HUD_ENABLED, v).apply(); }

    // ----- misc -------------------------------------------------------------------------------
    public boolean isInvertScroll() { return prefs.getBoolean(KEY_INVERT_SCROLL, false); }
    public void setInvertScroll(boolean v) { prefs.edit().putBoolean(KEY_INVERT_SCROLL, v).apply(); }

    public boolean isBootPromptEnabled() { return prefs.getBoolean(KEY_BOOT_PROMPT, false); }
    public void setBootPromptEnabled(boolean v) { prefs.edit().putBoolean(KEY_BOOT_PROMPT, v).apply(); }

    // ----- application profiles -----------------------------------------------------------------
    public GestureAction getAction(AppProfile profile, GestureType gesture) {
        GestureAction def = profile.defaultAction(gesture);
        return GestureAction.fromName(prefs.getString(mapKey(profile, gesture), def.name()), def);
    }

    public void setAction(AppProfile profile, GestureType gesture, GestureAction action) {
        prefs.edit().putString(mapKey(profile, gesture), action.name()).apply();
    }

    public void resetProfile(AppProfile profile) {
        SharedPreferences.Editor e = prefs.edit();
        for (GestureType g : GestureType.mappable()) e.remove(mapKey(profile, g));
        e.apply();
    }

    private static String mapKey(AppProfile p, GestureType g) {
        return KEY_MAP_PREFIX + p.name() + "." + g.name();
    }

    // ----- glue to the detector ---------------------------------------------------------------
    public GestureDetector.Config toConfig() {
        GestureDetector.Config c = new GestureDetector.Config();
        c.sensitivity = getSensitivity();
        c.smoothing = getSmoothing();
        c.pinchThreshold = getPinchThreshold();
        c.swipeDistance = getSwipeDistance();
        c.swipeSpeed = getSwipeSpeed();
        c.cooldownMs = getCooldownMs();
        return c;
    }
}
