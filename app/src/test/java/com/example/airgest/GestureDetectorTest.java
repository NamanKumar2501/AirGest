package com.example.airgest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM tests for the gesture state machine using synthetic hand landmarks.
 * Run with: ./gradlew testDebugUnitTest
 */
public class GestureDetectorTest {

    // ---- synthetic hand -----------------------------------------------------------------------
    static final float L = 0.12f;      // palm length (wrist -> middle MCP) in normalised y units
    static final float ASPECT = 0.75f; // 480 x 640 upright frame

    enum P { OPEN, FIST, PINCH, ZOOM }

    /** Builds 21 landmarks. (px,py) = wrist position, gap = thumb-index gap for the ZOOM pose. */
    static HandFrame hand(long t, float px, float py, P pose, float gap) {
        float[] u = new float[21];
        float[] v = new float[21];
        // wrist + MCPs (palm units, u right, v up)
        u[0] = 0f;      v[0] = 0f;
        u[5] = -0.28f;  v[5] = 0.98f;   // index
        u[9] = 0f;      v[9] = 1.0f;    // middle
        u[13] = 0.26f;  v[13] = 0.96f;  // ring
        u[17] = 0.5f;   v[17] = 0.88f;  // pinky
        int[] mcp = {5, 9, 13, 17};
        boolean[] ext = {true, true, true, true};
        if (pose == P.FIST) ext = new boolean[]{false, false, false, false};
        if (pose == P.ZOOM) ext = new boolean[]{true, false, false, false};
        for (int f = 0; f < 4; f++) {
            int m = mcp[f];
            u[m + 1] = u[m];
            u[m + 3] = u[m];
            u[m + 2] = u[m];
            if (ext[f]) {
                v[m + 1] = v[m] + 0.45f;
                v[m + 2] = v[m] + 0.70f;
                v[m + 3] = v[m] + 0.90f;
            } else {
                v[m + 1] = v[m] + 0.40f;
                v[m + 2] = v[m] + 0.30f;
                v[m + 3] = v[m] - 0.45f;
            }
        }
        // thumb
        u[1] = -0.35f; v[1] = 0.25f;
        if (pose == P.OPEN) {
            u[2] = -0.6f; v[2] = 0.5f; u[3] = -0.85f; v[3] = 0.7f; u[4] = -1.1f; v[4] = 0.9f;
        } else if (pose == P.PINCH) {
            u[2] = -0.5f; v[2] = 0.7f; u[3] = -0.4f; v[3] = 1.3f; u[4] = u[8] + 0.02f; v[4] = v[8] - 0.1f;
        } else if (pose == P.ZOOM) {
            u[2] = -0.6f; v[2] = 0.5f; u[3] = -0.85f; v[3] = 0.9f; u[4] = u[8] - gap; v[4] = v[8];
        } else { // FIST: thumb tucked
            u[2] = -0.3f; v[2] = 0.35f; u[3] = -0.2f; v[3] = 0.5f; u[4] = -0.1f; v[4] = 0.6f;
        }
        float[] x = new float[21];
        float[] y = new float[21];
        for (int i = 0; i < 21; i++) {
            x[i] = px + u[i] * L / ASPECT;
            y[i] = py - v[i] * L;
        }
        return new HandFrame(true, x, y, 0.95f, t, ASPECT);
    }

    static class Rec implements GestureDetector.Listener {
        final List<GestureType> gestures = new ArrayList<>();
        final List<Float> gx = new ArrayList<>();
        final List<GestureState> states = new ArrayList<>();
        int dragStarts, dragMoves, dragEnds;
        float lastDragX;
        final List<GestureDetector.Mode> modes = new ArrayList<>();
        public void onStateChanged(GestureState s) { states.add(s); }
        public void onModeChanged(GestureDetector.Mode m) { modes.add(m); }
        public void onHandPresence(boolean p) { }
        public void onCursorMoved(float nx, float ny) { }
        public void onGesture(GestureType t, float c, float nx, float ny) { gestures.add(t); gx.add(nx); }
        public void onDragStart(float nx, float ny) { dragStarts++; }
        public void onDragMove(float nx, float ny) { dragMoves++; lastDragX = nx; }
        public void onDragEnd(float nx, float ny) { dragEnds++; }
        int count(GestureType t) { int n = 0; for (GestureType g : gestures) if (g == t) n++; return n; }
    }

    /** Frame driver at 30 fps. */
    static class Sim {
        final GestureDetector det;
        final Rec rec;
        long t = 1000;
        float px = 0.3f, py = 0.75f;
        P pose = P.OPEN;
        float gap = 0.5f;
        Sim(Rec r) { rec = r; det = new GestureDetector(r); }
        void frame() { det.onFrame(hand(t, px, py, pose, gap)); t += 33; }
        void hold(long ms) { long end = t + ms; while (t < end) frame(); }
        void move(float dx, float dy, long ms) {
            float sx = px, sy = py; long start = t;
            while (t < start + ms) {
                float k = (t - start) / (float) ms;
                px = sx + dx * k; py = sy + dy * k;
                frame();
            }
            px = sx + dx; py = sy + dy;
        }
        void noHand(long ms) { long end = t + ms; while (t < end) { det.onFrame(HandFrame.none(t)); t += 33; } }
    }

    // ---- tests --------------------------------------------------------------------------------

    @Test
    public void swipeRightFiresExactlyOnceEvenIfHandStaysThere() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.px = 0.2f;
        s.hold(600);                 // hand raised, resting -> armed
        s.move(0.45f, 0f, 260);      // fast left -> right
        s.hold(2500);                // stays at the end position for 2.5 s
        assertEquals(1, r.count(GestureType.SWIPE_RIGHT));
        assertEquals(0, r.count(GestureType.SWIPE_LEFT));
        assertEquals(1, r.gestures.size());
    }

    @Test
    public void returnStrokeAfterSwipeIsIgnoredThenSwipeLeftWorks() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.px = 0.2f;
        s.hold(600);
        s.move(0.45f, 0f, 260);      // swipe right
        s.move(-0.45f, 0f, 320);     // natural return stroke, immediately, no pause
        s.hold(200);
        assertEquals(1, r.count(GestureType.SWIPE_RIGHT));
        assertEquals("return stroke must not fire", 0, r.count(GestureType.SWIPE_LEFT));
        s.hold(1500);                // rest -> re-armed
        s.move(-0.45f, 0f, 260);     // deliberate swipe left
        s.hold(1500);
        assertEquals(1, r.count(GestureType.SWIPE_LEFT));
        assertEquals(1, r.count(GestureType.SWIPE_RIGHT));
    }

    @Test
    public void slowMovementIsNotASwipe() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.px = 0.2f;
        s.hold(600);
        s.move(0.45f, 0f, 2500);
        s.hold(500);
        assertEquals(0, r.count(GestureType.SWIPE_RIGHT));
        assertEquals(0, r.count(GestureType.SWIPE_LEFT));
    }

    @Test
    public void diagonalMovementIsNotASwipe() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.px = 0.2f; s.py = 0.6f;
        s.hold(600);
        s.move(0.3f, 0.3f, 250);
        s.hold(500);
        assertEquals(0, r.count(GestureType.SWIPE_RIGHT));
    }

    @Test
    public void handEnteringQuicklyWithoutRestIsNotASwipe() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.px = 0.1f;
        s.move(0.5f, 0f, 300);       // hand appears and flies across immediately
        s.hold(1000);
        assertEquals(0, r.gestures.size());
    }

    @Test
    public void tinyJitterDoesNothing() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.hold(500);
        for (int i = 0; i < 90; i++) { s.px += (i % 2 == 0 ? 0.004f : -0.004f); s.frame(); }
        assertEquals(0, r.count(GestureType.SWIPE_RIGHT) + r.count(GestureType.SWIPE_LEFT));
    }

    @Test
    public void singlePinchBecomesOneClickAfterDoubleWindow() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.hold(600);
        s.pose = P.PINCH; s.hold(300);
        s.pose = P.OPEN;  s.hold(1200);
        assertEquals(1, r.count(GestureType.PINCH));
        assertEquals(0, r.count(GestureType.DOUBLE_PINCH));
        assertEquals(1, r.gestures.size());
    }

    @Test
    public void twoQuickPinchesAreOneDoubleClickAndNoSingleClick() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.hold(600);
        s.pose = P.PINCH; s.hold(150);
        s.pose = P.OPEN;  s.hold(130);
        s.pose = P.PINCH; s.hold(150);
        s.pose = P.OPEN;  s.hold(1200);
        assertEquals(1, r.count(GestureType.DOUBLE_PINCH));
        assertEquals(0, r.count(GestureType.PINCH));
        assertEquals(1, r.gestures.size());
    }

    @Test
    public void heldPinchDoesNotRepeat() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.hold(600);
        s.pose = P.PINCH; s.hold(3000);
        assertEquals(1, r.gestures.size());
    }

    @Test
    public void openPalmScrollsUpWhileHandRaisedAndStopsWhenReturned() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.py = 0.8f;
        s.hold(900);                 // open palm held still -> scroll mode
        assertTrue(r.modes.contains(GestureDetector.Mode.SCROLL));
        s.move(0f, -0.2f, 350);      // hand goes UP
        s.hold(2000);
        int ups = r.count(GestureType.OPEN_PALM_UP);
        assertTrue("expected repeated scroll-up steps, got " + ups, ups >= 2);
        assertEquals(0, r.count(GestureType.OPEN_PALM_DOWN));
        assertEquals(0, r.count(GestureType.SWIPE_LEFT) + r.count(GestureType.SWIPE_RIGHT));
        s.move(0f, 0.2f, 600);       // back to the neutral position
        int before = r.gestures.size();
        s.hold(2000);
        assertEquals("no scrolling at neutral position", before, r.gestures.size());
    }

    @Test
    public void fistHoldsAndDragsThenReleases() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.hold(400);
        s.pose = P.FIST;
        s.hold(200);
        assertEquals("must not start immediately", 0, r.dragStarts);
        s.hold(500);
        assertEquals(1, r.dragStarts);
        s.move(0.15f, 0.05f, 500);
        s.hold(300);
        assertTrue(r.dragMoves > 5);
        assertEquals(0, r.dragEnds);
        s.pose = P.OPEN;
        s.hold(600);
        assertEquals(1, r.dragEnds);
        assertEquals(0, r.gestures.size());
    }

    @Test
    public void losingTheHandWhileDraggingReleasesTheDrag() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.hold(300);
        s.pose = P.FIST;
        s.hold(800);
        assertEquals(1, r.dragStarts);
        s.noHand(800);
        assertEquals(1, r.dragEnds);
    }

    @Test
    public void twoFingerZoomInAndOut() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.hold(300);
        s.pose = P.ZOOM; s.gap = 0.4f;
        s.hold(700);
        assertTrue(r.modes.contains(GestureDetector.Mode.ZOOM));
        for (int i = 0; i < 15; i++) { s.gap += 0.08f; s.frame(); }   // fingers apart
        s.hold(800);
        assertTrue("zoom in expected", r.count(GestureType.ZOOM_IN) >= 1);
        assertEquals(0, r.count(GestureType.ZOOM_OUT));
        for (int i = 0; i < 20; i++) { s.gap -= 0.08f; s.frame(); }    // fingers together
        s.hold(800);
        assertTrue("zoom out expected", r.count(GestureType.ZOOM_OUT) >= 1);
    }

    @Test
    public void stateMachineFollowsTheDocumentedOrder() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.px = 0.2f;
        s.hold(600);
        s.move(0.45f, 0f, 260);
        s.hold(1500);
        List<GestureState> st = r.states;
        int iHand = st.indexOf(GestureState.HAND_DETECTED);
        int iTrack = st.indexOf(GestureState.TRACKING);
        int iCand = st.indexOf(GestureState.GESTURE_CANDIDATE);
        int iConf = st.indexOf(GestureState.GESTURE_CONFIRMED);
        int iExec = st.indexOf(GestureState.ACTION_EXECUTED);
        int iCool = st.indexOf(GestureState.COOLDOWN);
        assertTrue(iHand >= 0 && iHand < iTrack);
        assertTrue(iTrack < iCand && iCand < iConf && iConf < iExec && iExec < iCool);
    }

    @Test
    public void handLossResetsEverything() {
        Rec r = new Rec();
        Sim s = new Sim(r);
        s.hold(600);
        s.noHand(600);
        assertEquals(GestureState.IDLE, s.det.getState());
    }
}
