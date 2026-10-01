package com.example.airgest;

/**
 * Tiny in-process status board. The foreground service and the accessibility service write to
 * it; MainActivity and the Debug screen read it. Nothing here is persisted or sent anywhere.
 */
public final class AirControlState {
    private AirControlState() { }

    public static volatile boolean controllerActive = false;
    public static volatile boolean paused = false;           // e.g. screen off
    public static volatile boolean handDetected = false;
    public static volatile int indexXpx = 0;
    public static volatile int indexYpx = 0;
    public static volatile String gesture = "NONE";
    public static volatile float confidence = 0f;
    public static volatile int fps = 0;
    public static volatile String gestureState = "IDLE";
    public static volatile String mode = "NONE";
    public static volatile String pose = "NONE";
    public static volatile String lastAction = "-";
    public static volatile String foregroundPackage = "-";
    public static volatile String lastError = "";

    public static void resetRuntime() {
        handDetected = false;
        indexXpx = 0;
        indexYpx = 0;
        gesture = "NONE";
        confidence = 0f;
        fps = 0;
        gestureState = "IDLE";
        mode = "NONE";
        pose = "NONE";
        lastAction = "-";
    }
}
