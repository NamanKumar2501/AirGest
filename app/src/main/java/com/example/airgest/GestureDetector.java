package com.example.airgest;

import java.util.ArrayList;

/**
 * Turns a stream of hand-landmark frames into discrete gestures using an explicit state machine.
 *
 * <pre>
 * IDLE -> HAND_DETECTED -> TRACKING -> GESTURE_CANDIDATE -> GESTURE_CONFIRMED
 *      -> ACTION_EXECUTED -> COOLDOWN -> IDLE
 * </pre>
 *
 * Nothing here fires "per frame". A swipe must be seen on consecutive frames, fires exactly once,
 * then the detector sits in COOLDOWN and afterwards needs the hand to come to rest before it is
 * "armed" again - this also swallows the natural return-stroke of the hand after a swipe.
 *
 * Pure Java on purpose (no Android classes) so it can be unit-tested on the JVM.
 * Not thread-safe: feed it from a single thread.
 */
public class GestureDetector {

    /** Continuous modes that last while a pose is held. */
    public enum Mode { NONE, SCROLL, DRAG, ZOOM }

    /** Static hand poses. */
    public enum Pose { NONE, OPEN_PALM, FIST, PINCH, POINT, ZOOM, OTHER }

    public interface Listener {
        void onStateChanged(GestureState state);
        void onModeChanged(Mode mode);
        void onHandPresence(boolean present);
        /** Smoothed pointer position, normalised 0..1 of the SCREEN. */
        void onCursorMoved(float nx, float ny);
        /** A discrete gesture fired exactly once. Coordinates are the pointer position (0..1). */
        void onGesture(GestureType type, float confidence, float nx, float ny);
        void onDragStart(float nx, float ny);
        void onDragMove(float nx, float ny);
        void onDragEnd(float nx, float ny);
    }

    /** User-tunable thresholds (see Calibration screen). */
    public static class Config {
        /** How far the pointer moves on screen per unit of hand movement. */
        public float sensitivity = 1.8f;
        /** 0 = raw, 0.9 = very smooth. */
        public float smoothing = 0.55f;
        /** thumb-tip to index-tip distance / palm size below which we call it a pinch. */
        public float pinchThreshold = 0.32f;
        /** Minimum horizontal travel of the fingertip, as a fraction of the camera width. */
        public float swipeDistance = 0.28f;
        /** Minimum horizontal speed in camera-widths per second. */
        public float swipeSpeed = 0.9f;
        /** Pause after a successful swipe. */
        public long cooldownMs = 800;
        /** Two pinches within this time are a double pinch (0 disables double pinch). */
        public long doublePinchMs = 450;
        /** Open-palm scroll: vertical distance from the start point that starts scrolling. */
        public float scrollDeadzone = 0.10f;
        /** Zoom: change of the thumb-index distance ratio per zoom step. */
        public float zoomStep = 0.35f;

        public Config copy() {
            Config c = new Config();
            c.sensitivity = sensitivity;
            c.smoothing = smoothing;
            c.pinchThreshold = pinchThreshold;
            c.swipeDistance = swipeDistance;
            c.swipeSpeed = swipeSpeed;
            c.cooldownMs = cooldownMs;
            c.doublePinchMs = doublePinchMs;
            c.scrollDeadzone = scrollDeadzone;
            c.zoomStep = zoomStep;
            return c;
        }
    }

    // ----- fixed tuning constants ------------------------------------------------------------
    private static final long POSE_DEBOUNCE_MS = 60;
    private static final long HAND_STABLE_MS = 100;
    private static final long HAND_LOST_MS = 250;
    private static final long SWIPE_MIN_MS = 80;
    private static final long SWIPE_MAX_MS = 650;
    private static final int SWIPE_CONFIRM_FRAMES = 2;
    private static final float MAX_VERTICAL_RATIO = 0.45f;   // |dy| must be < 45% of |dx|
    private static final float MAX_PATH_RATIO = 1.4f;        // path must be fairly straight
    private static final float REST_SPEED = 0.30f;           // camera-units/second
    private static final long REST_MS = 150;
    private static final long SCROLL_HOLD_MS = 350;
    private static final long DRAG_HOLD_MS = 350;
    private static final long ZOOM_HOLD_MS = 350;
    private static final long DRAG_RELEASE_MS = 200;
    private static final long MODE_EXIT_MS = 250;
    private static final long CURSOR_LOOKBACK_MS = 140;
    private static final long CLICK_COOLDOWN_MS = 250;
    private static final long DRAG_COOLDOWN_MS = 500;
    private static final long MAX_DRAG_MS = 30000;
    private static final long ZOOM_INTERVAL_MS = 600;
    private static final float THUMB_EXTENDED_RATIO = 0.65f;
    private static final float FINGER_EXTENDED_RATIO = 1.12f;

    private enum Cand { NONE, SWIPE, PINCH, MODE }

    private static class Sample {
        final long t;
        final float x, y;
        Sample(long t, float x, float y) { this.t = t; this.x = x; this.y = y; }
    }

    private final Listener listener;
    private Config cfg = new Config();

    // state machine
    private GestureState state = GestureState.IDLE;
    private Mode mode = Mode.NONE;
    private Pose pose = Pose.NONE;
    private Pose rawPose = Pose.NONE;
    private long poseSince, rawSince;
    private long cooldownUntil;
    private Cand cand = Cand.NONE;
    private GestureType candSwipe = GestureType.NONE;
    private Mode candMode = Mode.NONE;
    private int candFrames;

    // presence
    private boolean handPresent;
    private long handSince, lastSeen, lastFrameT;

    // per-frame geometry
    private boolean idxExt, midExt, ringExt, pnkExt, thumbExt;
    private float pinchRatio;
    private float palmX, palmY, tipX, tipY;

    // swipe / rest
    private final ArrayList<Sample> swipeHist = new ArrayList<>();
    private long restMs;
    private boolean armed;
    private float lastSwipeQuality = 1f;

    // cursor
    private final ArrayList<Sample> cursorHist = new ArrayList<>();
    private boolean cursorInit;
    private float cx = 0.5f, cy = 0.5f;
    private boolean frozen;

    // pinch
    private boolean pinchHandled;
    private long pendingClickT;
    private float pendingX, pendingY, pendingConf;

    // scroll
    private float scrollAnchorX, scrollAnchorY;
    // zoom
    private float zoomAnchor, zoomSmoothed;
    // drag
    private float dragStartX, dragStartY, dragRefX, dragRefY;
    private long dragStartT;

    public GestureDetector(Listener listener) {
        this.listener = listener;
    }

    public void setConfig(Config config) {
        this.cfg = config.copy();
    }

    // ----- getters for the debug screen ------------------------------------------------------
    public GestureState getState() { return state; }
    public Mode getMode() { return mode; }
    public Pose getPose() { return pose; }
    public float getCursorX() { return cx; }
    public float getCursorY() { return cy; }
    public float getPinchRatio() { return pinchRatio; }
    public boolean isArmed() { return armed; }

    /** Drop everything (camera paused / stopped). Releases an active drag. */
    public void reset() {
        if (mode == Mode.DRAG) {
            listener.onDragEnd(cx, cy);
        }
        setMode(Mode.NONE);
        boolean wasPresent = handPresent;
        handPresent = false;
        swipeHist.clear();
        cursorHist.clear();
        cursorInit = false;
        frozen = false;
        cand = Cand.NONE;
        candFrames = 0;
        pendingClickT = 0;
        pinchHandled = false;
        armed = false;
        restMs = 0;
        pose = Pose.NONE;
        rawPose = Pose.NONE;
        cooldownUntil = 0;
        setState(GestureState.IDLE);
        if (wasPresent) listener.onHandPresence(false);
    }

    // =========================================================================================
    // main entry point
    // =========================================================================================
    public void onFrame(HandFrame f) {
        final long now = f.timestampMs;
        if (lastFrameT == 0) lastFrameT = now;

        if (state == GestureState.COOLDOWN && now >= cooldownUntil) {
            setState(handPresent && mode != Mode.NONE ? GestureState.TRACKING : GestureState.IDLE);
        }
        resolvePendingClick(now);

        if (!f.present) {
            onHandMissing(now);
            lastFrameT = now;
            return;
        }

        if (!handPresent) {
            handPresent = true;
            handSince = now;
            listener.onHandPresence(true);
        }
        lastSeen = now;

        analyse(f);
        updatePose(now);
        updateCursor(now);
        recordSwipeSample(now);
        updateRest(now);

        if (state == GestureState.IDLE) setState(GestureState.HAND_DETECTED);
        if (state == GestureState.HAND_DETECTED) {
            if (now - handSince >= HAND_STABLE_MS) {
                setState(GestureState.TRACKING);
            } else {
                lastFrameT = now;
                return;
            }
        }

        final float conf = f.confidence;
        processModes(now, conf);
        processPinch(now, conf);
        processSwipe(now, conf);
        processModeEntry(now, conf);
        lastFrameT = now;
    }

    // =========================================================================================
    // geometry & pose
    // =========================================================================================
    private static float dist(HandFrame f, int a, int b) {
        float dx = (f.x[a] - f.x[b]) * f.aspect;
        float dy = f.y[a] - f.y[b];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean fingerExtended(HandFrame f, int pip, int tip) {
        return dist(f, tip, HandFrame.WRIST) > dist(f, pip, HandFrame.WRIST) * FINGER_EXTENDED_RATIO;
    }

    private void analyse(HandFrame f) {
        float palm = Math.max(dist(f, HandFrame.WRIST, HandFrame.MIDDLE_MCP), 1e-4f);
        idxExt = fingerExtended(f, HandFrame.INDEX_PIP, HandFrame.INDEX_TIP);
        midExt = fingerExtended(f, HandFrame.MIDDLE_PIP, HandFrame.MIDDLE_TIP);
        ringExt = fingerExtended(f, HandFrame.RING_PIP, HandFrame.RING_TIP);
        pnkExt = fingerExtended(f, HandFrame.PINKY_PIP, HandFrame.PINKY_TIP);
        thumbExt = dist(f, HandFrame.THUMB_TIP, HandFrame.INDEX_MCP) / palm > THUMB_EXTENDED_RATIO;
        pinchRatio = dist(f, HandFrame.THUMB_TIP, HandFrame.INDEX_TIP) / palm;

        palmX = (f.x[0] + f.x[5] + f.x[9] + f.x[13] + f.x[17]) / 5f;
        palmY = (f.y[0] + f.y[5] + f.y[9] + f.y[13] + f.y[17]) / 5f;
        tipX = f.x[HandFrame.INDEX_TIP];
        tipY = f.y[HandFrame.INDEX_TIP];
    }

    private Pose classify() {
        boolean pinching = rawPose == Pose.PINCH;
        float limit = pinching ? cfg.pinchThreshold * 1.5f : cfg.pinchThreshold;
        boolean pinchClosed = pinchRatio < limit;
        if (pinchClosed && (midExt || ringExt || pnkExt)) return Pose.PINCH;
        if (!idxExt && !midExt && !ringExt && !pnkExt) return Pose.FIST;
        if (idxExt && midExt && ringExt && pnkExt) return Pose.OPEN_PALM;
        if (thumbExt && idxExt && !midExt && !ringExt && !pnkExt) return Pose.ZOOM;
        if (idxExt && !midExt && !ringExt && !pnkExt) return Pose.POINT;
        return Pose.OTHER;
    }

    private static boolean isHold(Pose p) {
        return p == Pose.PINCH || p == Pose.FIST || p == Pose.ZOOM;
    }

    private void updatePose(long now) {
        Pose raw = classify();
        if (raw != rawPose) {
            boolean wasHold = isHold(rawPose);
            rawPose = raw;
            rawSince = now;
            if (isHold(raw) && !wasHold) {
                freezeCursorFrom(now - CURSOR_LOOKBACK_MS);
            } else if (!isHold(raw) && wasHold) {
                frozen = false;
            }
        }
        if (raw != pose && now - rawSince >= POSE_DEBOUNCE_MS) {
            // entering / leaving a hold pose invalidates the swipe history (the fingertip moved
            // because the fingers moved, not because the hand travelled)
            if (isHold(pose) != isHold(raw)) swipeHist.clear();
            pose = raw;
            poseSince = now;
        }
        if (pose == Pose.ZOOM) {
            zoomSmoothed += (pinchRatio - zoomSmoothed) * 0.5f;
        } else {
            zoomSmoothed = pinchRatio;
        }
    }

    // =========================================================================================
    // cursor
    // =========================================================================================
    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private void updateCursor(long now) {
        if (mode == Mode.DRAG) return; // the drag code owns the pointer
        if (frozen) return;
        float tx = clamp01(0.5f + (tipX - 0.5f) * cfg.sensitivity);
        float ty = clamp01(0.5f + (tipY - 0.5f) * cfg.sensitivity);
        if (!cursorInit) {
            cx = tx;
            cy = ty;
            cursorInit = true;
        } else {
            float alpha = 1f - Math.min(0.95f, Math.max(0f, cfg.smoothing));
            cx += (tx - cx) * alpha;
            cy += (ty - cy) * alpha;
        }
        cursorHist.add(new Sample(now, cx, cy));
        trim(cursorHist, now, 700);
        listener.onCursorMoved(cx, cy);
    }

    private void freezeCursorFrom(long t) {
        if (cursorHist.isEmpty()) {
            frozen = true;
            return;
        }
        Sample pick = cursorHist.get(0);
        for (Sample s : cursorHist) {
            if (s.t <= t) pick = s; else break;
        }
        cx = pick.x;
        cy = pick.y;
        frozen = true;
        listener.onCursorMoved(cx, cy);
    }

    private static void trim(ArrayList<Sample> list, long now, long keepMs) {
        while (!list.isEmpty() && now - list.get(0).t > keepMs) list.remove(0);
    }

    // =========================================================================================
    // swipe
    // =========================================================================================
    private void recordSwipeSample(long now) {
        swipeHist.add(new Sample(now, tipX, tipY));
        trim(swipeHist, now, SWIPE_MAX_MS + 100);
    }

    private void updateRest(long now) {
        long dt = Math.min(200, Math.max(0, now - lastFrameT));
        float v = recentSpeed(now, 150);
        if (v < REST_SPEED) restMs += dt; else restMs = 0;
        if (restMs >= REST_MS) armed = true;
    }

    private float recentSpeed(long now, long windowMs) {
        Sample first = null;
        for (Sample s : swipeHist) {
            if (now - s.t <= windowMs) { first = s; break; }
        }
        Sample last = swipeHist.isEmpty() ? null : swipeHist.get(swipeHist.size() - 1);
        if (first == null || last == null || last.t - first.t < 30) return 0f;
        float dx = last.x - first.x, dy = last.y - first.y;
        return (float) Math.sqrt(dx * dx + dy * dy) / ((last.t - first.t) / 1000f);
    }

    private GestureType evalSwipe(long now) {
        if (swipeHist.size() < 3) return GestureType.NONE;
        final int n = swipeHist.size();
        final Sample last = swipeHist.get(n - 1);
        for (int i = 0; i < n - 1; i++) {
            Sample s = swipeHist.get(i);
            long dt = last.t - s.t;
            if (dt < SWIPE_MIN_MS) break;
            if (dt > SWIPE_MAX_MS) continue;
            float dx = last.x - s.x;
            float dy = last.y - s.y;
            float adx = Math.abs(dx);
            if (adx < cfg.swipeDistance) continue;
            float speed = adx / (dt / 1000f);
            if (speed < cfg.swipeSpeed) continue;
            if (Math.abs(dy) > MAX_VERTICAL_RATIO * adx) continue;
            float path = 0f;
            for (int k = i; k < n - 1; k++) {
                path += Math.abs(swipeHist.get(k + 1).x - swipeHist.get(k).x);
            }
            if (path > MAX_PATH_RATIO * adx) continue;
            float q = 0.6f + 0.2f * Math.min(2f, adx / cfg.swipeDistance - 1f)
                    + 0.2f * Math.min(2f, speed / cfg.swipeSpeed - 1f);
            lastSwipeQuality = Math.max(0.5f, Math.min(1f, q));
            return dx > 0 ? GestureType.SWIPE_RIGHT : GestureType.SWIPE_LEFT;
        }
        return GestureType.NONE;
    }

    private void processSwipe(long now, float conf) {
        if (mode == Mode.DRAG || mode == Mode.ZOOM) return;
        boolean inSwipeCandidate = state == GestureState.GESTURE_CANDIDATE && cand == Cand.SWIPE;
        if (!(state == GestureState.TRACKING || inSwipeCandidate)) return;

        if (!armed || isHold(pose)) {
            if (inSwipeCandidate) clearCandidate();
            return;
        }
        GestureType s = evalSwipe(now);
        if (s == GestureType.NONE) {
            if (inSwipeCandidate) clearCandidate();
            return;
        }
        if (cand == Cand.SWIPE && candSwipe == s) {
            candFrames++;
        } else {
            candSwipe = s;
            candFrames = 1;
            setCandidate(Cand.SWIPE);
        }
        if (candFrames >= SWIPE_CONFIRM_FRAMES) {
            swipeHist.clear();
            armed = false;
            restMs = 0;
            execute(s, conf * lastSwipeQuality, cx, cy, cfg.cooldownMs, now);
        }
    }

    // =========================================================================================
    // pinch / double pinch
    // =========================================================================================
    private void processPinch(long now, float conf) {
        if (pose != Pose.PINCH) {
            pinchHandled = false;
            return;
        }
        if (pinchHandled) return;
        boolean secondPinch = state == GestureState.GESTURE_CANDIDATE && cand == Cand.PINCH && pendingClickT != 0;
        if (!(state == GestureState.TRACKING || secondPinch)) {
            pinchHandled = true; // consumed: never fire late, after a cooldown ends
            return;
        }
        pinchHandled = true;
        if (secondPinch) {
            if (now - pendingClickT <= cfg.doublePinchMs) {
                float px = pendingX, py = pendingY;
                pendingClickT = 0;
                execute(GestureType.DOUBLE_PINCH, conf, px, py, CLICK_COOLDOWN_MS, now);
            }
            return;
        }
        if (cfg.doublePinchMs <= 0) {
            execute(GestureType.PINCH, conf, cx, cy, CLICK_COOLDOWN_MS, now);
            return;
        }
        pendingClickT = now;
        pendingX = cx;
        pendingY = cy;
        pendingConf = conf;
        setCandidate(Cand.PINCH);
    }

    /** A single pinch is only released as a click once the double-pinch window has expired. */
    private void resolvePendingClick(long now) {
        if (pendingClickT != 0 && now - pendingClickT > cfg.doublePinchMs) {
            float px = pendingX, py = pendingY, pc = pendingConf;
            pendingClickT = 0;
            execute(GestureType.PINCH, pc, px, py, CLICK_COOLDOWN_MS, now);
        }
    }

    // =========================================================================================
    // continuous modes: scroll, drag, zoom
    // =========================================================================================
    private void processModes(long now, float conf) {
        switch (mode) {
            case DRAG:
                if (now - dragStartT > MAX_DRAG_MS
                        || (pose != Pose.FIST && now - poseSince >= DRAG_RELEASE_MS)) {
                    endDrag(now);
                } else if (pose == Pose.FIST) {
                    moveDrag();
                }
                break;
            case SCROLL:
                if (pose != Pose.OPEN_PALM && (pose != Pose.OTHER || now - poseSince > MODE_EXIT_MS)) {
                    setMode(Mode.NONE);
                } else if (state == GestureState.TRACKING) {
                    scrollStep(now, conf);
                }
                break;
            case ZOOM:
                if (pose != Pose.ZOOM && now - poseSince >= MODE_EXIT_MS) {
                    setMode(Mode.NONE);
                } else if (state == GestureState.TRACKING && pose == Pose.ZOOM) {
                    zoomStep(now, conf);
                }
                break;
            default:
                break;
        }
    }

    private void processModeEntry(long now, float conf) {
        if (mode != Mode.NONE) return;
        Mode want = pose == Pose.OPEN_PALM ? Mode.SCROLL
                : pose == Pose.FIST ? Mode.DRAG
                : pose == Pose.ZOOM ? Mode.ZOOM : Mode.NONE;
        boolean inModeCandidate = state == GestureState.GESTURE_CANDIDATE && cand == Cand.MODE;
        if (want == Mode.NONE) {
            if (inModeCandidate) clearCandidate();
            return;
        }
        if (!(state == GestureState.TRACKING || inModeCandidate)) return;
        if (want == Mode.SCROLL && restMs < REST_MS) { // palm must be still, otherwise it is a swipe
            if (inModeCandidate) clearCandidate();
            return;
        }
        if (!inModeCandidate || candMode != want) {
            candMode = want;
            setCandidate(Cand.MODE);
        }
        long hold = want == Mode.SCROLL ? SCROLL_HOLD_MS : want == Mode.DRAG ? DRAG_HOLD_MS : ZOOM_HOLD_MS;
        if (now - poseSince < hold) return;

        // confirmed
        setState(GestureState.GESTURE_CONFIRMED);
        cand = Cand.NONE;
        candFrames = 0;
        switch (want) {
            case SCROLL:
                scrollAnchorX = palmX;
                scrollAnchorY = palmY;
                break;
            case ZOOM:
                zoomAnchor = zoomSmoothed;
                break;
            case DRAG:
                dragStartX = cx;
                dragStartY = cy;
                dragRefX = palmX;
                dragRefY = palmY;
                dragStartT = now;
                listener.onDragStart(cx, cy);
                break;
            default:
                break;
        }
        setMode(want);
        setState(GestureState.ACTION_EXECUTED);
        setState(GestureState.TRACKING);
    }

    private void scrollStep(long now, float conf) {
        float dy = palmY - scrollAnchorY;
        float dx = palmX - scrollAnchorX;
        float mag = Math.abs(dy);
        if (mag >= cfg.scrollDeadzone && mag > 1.2f * Math.abs(dx)) {
            long interval = (long) Math.max(300f, Math.min(700f, 700f - (mag - cfg.scrollDeadzone) * 2500f));
            execute(dy < 0 ? GestureType.OPEN_PALM_UP : GestureType.OPEN_PALM_DOWN, conf, cx, cy, interval, now);
        }
    }

    private void zoomStep(long now, float conf) {
        float delta = zoomSmoothed - zoomAnchor;
        if (delta >= cfg.zoomStep) {
            zoomAnchor = zoomSmoothed;
            execute(GestureType.ZOOM_IN, conf, cx, cy, ZOOM_INTERVAL_MS, now);
        } else if (delta <= -cfg.zoomStep) {
            zoomAnchor = zoomSmoothed;
            execute(GestureType.ZOOM_OUT, conf, cx, cy, ZOOM_INTERVAL_MS, now);
        }
    }

    private void moveDrag() {
        float tx = clamp01(dragStartX + (palmX - dragRefX) * cfg.sensitivity);
        float ty = clamp01(dragStartY + (palmY - dragRefY) * cfg.sensitivity);
        float alpha = 1f - Math.min(0.9f, Math.max(0f, cfg.smoothing)) * 0.6f;
        cx += (tx - cx) * alpha;
        cy += (ty - cy) * alpha;
        listener.onDragMove(cx, cy);
        listener.onCursorMoved(cx, cy);
    }

    private void endDrag(long now) {
        listener.onDragEnd(cx, cy);
        setMode(Mode.NONE);
        cursorHist.clear();
        swipeHist.clear();
        finishAction(DRAG_COOLDOWN_MS, now);
    }

    // =========================================================================================
    // hand lost
    // =========================================================================================
    private void onHandMissing(long now) {
        if (!handPresent) return;
        if (now - lastSeen < HAND_LOST_MS) return; // tolerate short detection drop-outs
        handPresent = false;
        listener.onHandPresence(false);
        if (mode == Mode.DRAG) {
            endDrag(now);
        }
        setMode(Mode.NONE);
        swipeHist.clear();
        pinchHandled = false;
        armed = false;
        restMs = 0;
        rawPose = Pose.NONE;
        pose = Pose.NONE;
        frozen = false;
        if (pendingClickT == 0) {
            cand = Cand.NONE;
            candFrames = 0;
            if (state != GestureState.COOLDOWN) setState(GestureState.IDLE);
        }
    }

    // =========================================================================================
    // state helpers
    // =========================================================================================
    private void setCandidate(Cand kind) {
        cand = kind;
        setState(GestureState.GESTURE_CANDIDATE);
    }

    private void clearCandidate() {
        cand = Cand.NONE;
        candFrames = 0;
        if (state == GestureState.GESTURE_CANDIDATE) setState(GestureState.TRACKING);
    }

    /** GESTURE_CONFIRMED -> (fire) -> ACTION_EXECUTED -> COOLDOWN. */
    private void execute(GestureType type, float conf, float x, float y, long cooldownMs, long now) {
        setState(GestureState.GESTURE_CONFIRMED);
        cand = Cand.NONE;
        candFrames = 0;
        pendingClickT = 0;
        listener.onGesture(type, Math.max(0f, Math.min(1f, conf)), x, y);
        finishAction(cooldownMs, now);
    }

    private void finishAction(long cooldownMs, long now) {
        setState(GestureState.ACTION_EXECUTED);
        cooldownUntil = now + cooldownMs;
        setState(GestureState.COOLDOWN);
    }

    private void setState(GestureState s) {
        if (state != s) {
            state = s;
            listener.onStateChanged(s);
        }
    }

    private void setMode(Mode m) {
        if (mode != m) {
            mode = m;
            listener.onModeChanged(m);
        }
    }
}
