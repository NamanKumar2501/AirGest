package com.example.airgest;

/**
 * Application profiles: the same hand gesture can do different things depending on which app is
 * in the foreground. The profile is chosen from the package name of the foreground window.
 * Anything that is not recognised uses the GALLERY profile (generic swipe left/right).
 */
public enum AppProfile {
    GALLERY("Gallery / Photos",
            new String[]{"photos", "gallery", "com.sec.android.gallery3d", "com.miui.gallery",
                    "com.android.gallery3d", "com.oneplus.gallery", "com.coloros.gallery3d",
                    "com.vivo.gallery", "camera"}),
    PDF_READER("PDF Reader",
            new String[]{"pdf", "adobe.reader", "docs.viewer", "com.google.android.apps.docs",
                    "xodo", "books"}),
    BROWSER("Browser",
            new String[]{"chrome", "browser", "firefox", "opera", "brave", "duckduckgo",
                    "com.microsoft.emmx", "sbrowser", "vivaldi"}),
    VIDEO_PLAYER("Video Player",
            new String[]{"youtube", "video", "vlc", "mxtech", "mxplayer", "netflix", "primevideo",
                    "hotstar", "player", "music", "spotify"});

    public final String displayName;
    private final String[] packageHints;

    AppProfile(String displayName, String[] packageHints) {
        this.displayName = displayName;
        this.packageHints = packageHints;
    }

    /** Picks the profile for a foreground package. Unknown apps use the GALLERY profile. */
    public static AppProfile resolve(String packageName) {
        if (packageName == null) return GALLERY;
        String p = packageName.toLowerCase();
        for (AppProfile profile : values()) {
            for (String hint : profile.packageHints) {
                if (p.contains(hint)) return profile;
            }
        }
        return GALLERY;
    }

    /** Factory mapping of gesture -> action for this profile. */
    public GestureAction defaultAction(GestureType g) {
        switch (g) {
            case SWIPE_LEFT:
                switch (this) {
                    case PDF_READER: return GestureAction.PREVIOUS_PAGE;
                    case BROWSER: return GestureAction.BACK;
                    case VIDEO_PLAYER: return GestureAction.MEDIA_PREVIOUS;
                    default: return GestureAction.PREVIOUS;
                }
            case SWIPE_RIGHT:
                switch (this) {
                    case PDF_READER: return GestureAction.NEXT_PAGE;
                    case BROWSER: return GestureAction.NONE;
                    case VIDEO_PLAYER: return GestureAction.MEDIA_NEXT;
                    default: return GestureAction.NEXT;
                }
            case PINCH: return GestureAction.CLICK;
            case DOUBLE_PINCH: return GestureAction.DOUBLE_CLICK;
            case OPEN_PALM_UP: return GestureAction.SCROLL_UP;
            case OPEN_PALM_DOWN: return GestureAction.SCROLL_DOWN;
            case FIST: return GestureAction.DRAG;
            case ZOOM_IN: return GestureAction.ZOOM_IN;
            case ZOOM_OUT: return GestureAction.ZOOM_OUT;
            default: return GestureAction.NONE;
        }
    }
}
