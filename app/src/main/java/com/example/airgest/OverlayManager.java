package com.example.airgest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Draws the Air Cursor and the gesture-confirmation HUD.
 *
 * Both are TYPE_ACCESSIBILITY_OVERLAY windows owned by the AccessibilityService. This overlay type
 * is granted to enabled accessibility services automatically - the user does NOT have to grant the
 * dangerous "Display over other apps" (SYSTEM_ALERT_WINDOW) permission. The windows are never
 * touchable and never take focus, so they can not interfere with the app underneath.
 *
 * All methods must be called on the main thread.
 */
public class OverlayManager {

    private final Context context;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    private CursorView cursorView;
    private WindowManager.LayoutParams cursorLp;
    private int cursorSizePx;

    private TextView hudView;
    private boolean hudAttached;
    private final Runnable fadeHud = new Runnable() {
        @Override public void run() {
            if (hudView != null && hudAttached) {
                hudView.animate().alpha(0f).setDuration(450).withEndAction(OverlayManager.this::removeHud).start();
            }
        }
    };

    public OverlayManager(Context serviceContext) {
        this.context = serviceContext;
        this.wm = (WindowManager) serviceContext.getSystemService(Context.WINDOW_SERVICE);
    }

    // =========================================================================================
    // helpers
    // =========================================================================================
    public Point getScreenSize() {
        Point p = new Point();
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            p.set(b.width(), b.height());
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
            p.set(dm.widthPixels, dm.heightPixels);
        }
        return p;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics()));
    }

    private WindowManager.LayoutParams baseParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    // =========================================================================================
    // Air Cursor
    // =========================================================================================
    public void showCursor(int sizeDp) {
        int glow = dp(Math.max(12, sizeDp) * 2.4f);
        if (cursorView != null && cursorSizePx == glow) return;
        hideCursor();
        cursorSizePx = glow;
        cursorView = new CursorView(context);
        cursorView.setContentDescription("Air Cursor");
        cursorLp = baseParams(glow, glow);
        Point s = getScreenSize();
        cursorLp.x = s.x / 2 - glow / 2;
        cursorLp.y = s.y / 2 - glow / 2;
        try {
            wm.addView(cursorView, cursorLp);
        } catch (Exception e) {
            cursorView = null;
            AirControlState.lastError = "Cursor overlay failed: " + e.getMessage();
        }
    }

    public void hideCursor() {
        if (cursorView != null) {
            try { wm.removeViewImmediate(cursorView); } catch (Exception ignored) { }
            cursorView = null;
        }
    }

    /** Position in screen pixels (centre of the cursor). */
    public void moveCursorPx(float x, float y) {
        if (cursorView == null) return;
        cursorLp.x = Math.round(x - cursorSizePx / 2f);
        cursorLp.y = Math.round(y - cursorSizePx / 2f);
        try { wm.updateViewLayout(cursorView, cursorLp); } catch (Exception ignored) { }
    }

    public void setCursorActive(boolean active) {
        if (cursorView != null) cursorView.setActive(active);
    }

    public void pulseCursor() {
        if (cursorView != null) {
            cursorView.animate().cancel();
            cursorView.setScaleX(1f);
            cursorView.setScaleY(1f);
            cursorView.animate().scaleX(0.55f).scaleY(0.55f).setDuration(90)
                    .withEndAction(() -> {
                        if (cursorView != null) cursorView.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    }).start();
        }
    }

    // =========================================================================================
    // Gesture confirmation HUD
    // =========================================================================================
    /**
     * Shows
     * <pre>
     * AIR CONTROL
     * ----------------
     * HAND: DETECTED
     * MODE: ACTIVE
     * GESTURE: SWIPE RIGHT
     * ACTION: NEXT IMAGE
     * </pre>
     * near the top of the screen for ~2 s, then fades out and removes itself.
     */
    public void showHud(String hand, String mode, String gesture, String action) {
        if (hudView == null) {
            hudView = new TextView(context);
            hudView.setTypeface(Typeface.MONOSPACE);
            hudView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            hudView.setTextColor(Color.WHITE);
            hudView.setBackgroundResource(R.drawable.bg_hud);
            hudView.setPadding(dp(14), dp(10), dp(14), dp(10));
            hudView.setLineSpacing(0f, 1.05f);
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        append(sb, "AIR CONTROL\n", Color.parseColor("#00E5FF"), true);
        append(sb, "----------------\n", Color.parseColor("#557799"), false);
        append(sb, "HAND: " + hand + "\n", Color.WHITE, false);
        append(sb, "MODE: " + mode + "\n", Color.WHITE, false);
        append(sb, "GESTURE: " + gesture + "\n", Color.parseColor("#B388FF"), false);
        append(sb, "ACTION: " + action, Color.parseColor("#69F0AE"), true);
        hudView.setText(sb);

        main.removeCallbacks(fadeHud);
        hudView.animate().cancel();
        hudView.setAlpha(0.92f);

        if (!hudAttached) {
            WindowManager.LayoutParams lp = baseParams(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = statusBarHeight() + dp(6);
            try {
                wm.addView(hudView, lp);
                hudAttached = true;
            } catch (Exception e) {
                AirControlState.lastError = "HUD overlay failed: " + e.getMessage();
                return;
            }
        }
        main.postDelayed(fadeHud, 1900);
    }

    private void removeHud() {
        main.removeCallbacks(fadeHud);
        if (hudView != null && hudAttached) {
            try { wm.removeViewImmediate(hudView); } catch (Exception ignored) { }
            hudAttached = false;
        }
    }

    private int statusBarHeight() {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : dp(24);
    }

    private static void append(SpannableStringBuilder sb, String text, int color, boolean bold) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Removes every overlay window. */
    public void destroy() {
        removeHud();
        hideCursor();
    }

    // =========================================================================================
    // Cursor view: neon glow, no permission-requiring tricks
    // =========================================================================================
    private static class CursorView extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean active;

        CursorView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            core.setStyle(Paint.Style.FILL);
        }

        void setActive(boolean a) {
            if (active != a) {
                active = a;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float outer = Math.min(w, h) / 2f;
            int main = active ? Color.parseColor("#B388FF") : Color.parseColor("#00E5FF");
            glow.setShader(new RadialGradient(cx, cy, outer,
                    new int[]{(main & 0x00FFFFFF) | 0xAA000000, (main & 0x00FFFFFF) | 0x33000000, 0x00000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, outer, glow);
            ring.setColor(main);
            ring.setStrokeWidth(outer * 0.09f);
            canvas.drawCircle(cx, cy, outer * 0.36f, ring);
            core.setColor(Color.WHITE);
            canvas.drawCircle(cx, cy, outer * 0.11f, core);
        }
    }
}
