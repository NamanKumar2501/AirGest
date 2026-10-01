package com.example.airgest;

/**
 * One tracking result. Landmarks use the MediaPipe 21-point layout, normalised to 0..1,
 * ALREADY MIRRORED (x grows when the user's hand moves to the user's right) and upright.
 */
public class HandFrame {
    public static final int WRIST = 0;
    public static final int THUMB_CMC = 1, THUMB_MCP = 2, THUMB_IP = 3, THUMB_TIP = 4;
    public static final int INDEX_MCP = 5, INDEX_PIP = 6, INDEX_DIP = 7, INDEX_TIP = 8;
    public static final int MIDDLE_MCP = 9, MIDDLE_PIP = 10, MIDDLE_DIP = 11, MIDDLE_TIP = 12;
    public static final int RING_MCP = 13, RING_PIP = 14, RING_DIP = 15, RING_TIP = 16;
    public static final int PINKY_MCP = 17, PINKY_PIP = 18, PINKY_DIP = 19, PINKY_TIP = 20;
    public static final int LANDMARK_COUNT = 21;

    public final boolean present;
    public final float[] x;
    public final float[] y;
    /** Hand detection confidence 0..1. */
    public final float confidence;
    /** Monotonic timestamp in milliseconds. */
    public final long timestampMs;
    /** Image width / height after rotation (needed for correct finger-length maths). */
    public final float aspect;

    public HandFrame(boolean present, float[] x, float[] y, float confidence, long timestampMs, float aspect) {
        this.present = present;
        this.x = x;
        this.y = y;
        this.confidence = confidence;
        this.timestampMs = timestampMs;
        this.aspect = aspect <= 0f ? 1f : aspect;
    }

    public static HandFrame none(long timestampMs) {
        return new HandFrame(false, null, null, 0f, timestampMs, 1f);
    }
}
