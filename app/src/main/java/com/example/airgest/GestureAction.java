package com.example.airgest;

/** Things Air Control can do on the phone. Gestures are mapped to these per app profile. */
public enum GestureAction {
    NONE("Do nothing", "NONE"),
    NEXT("Next image (swipe screen left)", "NEXT IMAGE"),
    PREVIOUS("Previous image (swipe screen right)", "PREVIOUS IMAGE"),
    NEXT_PAGE("Next page (swipe screen up)", "NEXT PAGE"),
    PREVIOUS_PAGE("Previous page (swipe screen down)", "PREVIOUS PAGE"),
    CLICK("Click (tap at cursor)", "CLICK"),
    DOUBLE_CLICK("Double click (double tap at cursor)", "DOUBLE CLICK"),
    SCROLL_UP("Scroll up", "SCROLL UP"),
    SCROLL_DOWN("Scroll down", "SCROLL DOWN"),
    DRAG("Hold / drag", "DRAG"),
    ZOOM_IN("Zoom in", "ZOOM IN"),
    ZOOM_OUT("Zoom out", "ZOOM OUT"),
    MEDIA_NEXT("Media: next track", "MEDIA NEXT"),
    MEDIA_PREVIOUS("Media: previous track", "MEDIA PREVIOUS"),
    MEDIA_PLAY_PAUSE("Media: play / pause", "PLAY / PAUSE"),
    BACK("System: Back", "BACK"),
    HOME("System: Home", "HOME"),
    RECENTS("System: Recent apps", "RECENT APPS");

    /** Text shown in the profile editor. */
    public final String displayName;
    /** Short upper-case text shown in the gesture overlay. */
    public final String overlayLabel;

    GestureAction(String displayName, String overlayLabel) {
        this.displayName = displayName;
        this.overlayLabel = overlayLabel;
    }

    public static GestureAction fromName(String name, GestureAction fallback) {
        if (name == null) return fallback;
        try {
            return GestureAction.valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
