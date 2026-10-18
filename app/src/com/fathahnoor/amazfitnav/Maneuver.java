package com.fathahnoor.amazfitnav;

import java.util.Locale;

/**
 * Classifies a Google Maps guidance string into a maneuver, so the watch can show a
 * bold arrow instead of relying on words alone.
 *
 * Maps' guidance language follows the phone locale, so English and Indonesian
 * spellings are both matched.
 */
public final class Maneuver {

    public enum Kind {
        // Only glyphs verified to render in the watch font are used. Block arrows
        // (U+2B05/2B06/27A1) are bolder than the thin ones; diagonals come from the
        // U+2196-2199 set. Hooked and circular arrows (\u21a9 \u21bb \u21b0) render as
        // blanks on the T-Rex Pro, so a U-turn leans on the bold down arrow instead
        // and a roundabout on the straight one.
        STRAIGHT(R.drawable.ic_nav_straight, "Continue", "\u2b06"),
        SLIGHT_LEFT(R.drawable.ic_nav_slight_left, "Keep left", "\u2196"),
        SLIGHT_RIGHT(R.drawable.ic_nav_slight_right, "Keep right", "\u2197"),
        LEFT(R.drawable.ic_nav_left, "Turn left", "\u2b05"),
        RIGHT(R.drawable.ic_nav_right, "Turn right", "\u27a1"),
        UTURN(R.drawable.ic_nav_uturn, "U-turn", "\u2b07"),
        ROUNDABOUT(R.drawable.ic_nav_roundabout, "Roundabout", "\u2b06"),
        ARRIVE(R.drawable.ic_nav_arrive, "Arriving", "\u2b06");

        public final int iconRes;
        public final String label;
        /**
         * Arrow drawn into the notification text itself. Zepp relays text but not our
         * small icon, and the watch font renders these glyphs (verified on-device).
         */
        public final String glyph;

        Kind(int iconRes, String label, String glyph) {
            this.iconRes = iconRes;
            this.label = label;
            this.glyph = glyph;
        }
    }

    private Maneuver() {}

    public static Kind of(String instruction) {
        if (instruction == null || instruction.isEmpty()) return Kind.STRAIGHT;
        String s = instruction.toLowerCase(Locale.ROOT);

        // Most specific first: "turn slightly right" must not be read as "turn right".
        if (contains(s, "u-turn", "u turn", "uturn", "putar balik", "balik arah")) {
            return Kind.UTURN;
        }
        if (contains(s, "roundabout", "rotary", "bundaran", "take the", "ambil jalan keluar")) {
            return Kind.ROUNDABOUT;
        }
        if (contains(s, "arriv", "destination", "tiba", "sampai di tujuan", "tujuan anda")) {
            return Kind.ARRIVE;
        }
        boolean slight = contains(s, "slight", "sedikit", "bear ", "keep left", "keep right",
                "tetap di");
        boolean left = contains(s, "left", "kiri");
        boolean right = contains(s, "right", "kanan");

        if (slight && left) return Kind.SLIGHT_LEFT;
        if (slight && right) return Kind.SLIGHT_RIGHT;
        if (left) return Kind.LEFT;
        if (right) return Kind.RIGHT;
        return Kind.STRAIGHT;
    }

    private static boolean contains(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
