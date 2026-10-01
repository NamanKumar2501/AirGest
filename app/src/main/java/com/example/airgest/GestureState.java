package com.example.airgest;

/**
 * Life-cycle of a gesture. A gesture is never fired "per frame": it has to walk through
 * these states, and can only fire once per pass.
 *
 * IDLE -> HAND_DETECTED -> TRACKING -> GESTURE_CANDIDATE -> GESTURE_CONFIRMED
 *      -> ACTION_EXECUTED -> COOLDOWN -> IDLE
 */
public enum GestureState {
    IDLE,
    HAND_DETECTED,
    TRACKING,
    GESTURE_CANDIDATE,
    GESTURE_CONFIRMED,
    ACTION_EXECUTED,
    COOLDOWN
}
