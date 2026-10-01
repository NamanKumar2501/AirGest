package com.example.airgest;

/** Hand gestures recognised by {@link GestureDetector}. */
public enum GestureType {
    NONE("NONE"),
    SWIPE_LEFT("SWIPE LEFT"),
    SWIPE_RIGHT("SWIPE RIGHT"),
    PINCH("PINCH"),
    DOUBLE_PINCH("DOUBLE PINCH"),
    OPEN_PALM_UP("OPEN PALM UP"),
    OPEN_PALM_DOWN("OPEN PALM DOWN"),
    FIST("FIST"),
    ZOOM_IN("TWO-FINGER ZOOM IN"),
    ZOOM_OUT("TWO-FINGER ZOOM OUT");

    public final String label;

    GestureType(String label) {
        this.label = label;
    }

    /** Gestures that can be given a custom action in an application profile. */
    public static GestureType[] mappable() {
        return new GestureType[]{
                SWIPE_LEFT, SWIPE_RIGHT, PINCH, DOUBLE_PINCH,
                OPEN_PALM_UP, OPEN_PALM_DOWN, FIST, ZOOM_IN, ZOOM_OUT
        };
    }
}
