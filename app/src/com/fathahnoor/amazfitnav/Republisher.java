package com.fathahnoor.amazfitnav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

/**
 * Re-emits Maps navigation as an ordinary notification.
 *
 * Why: Maps' own notification is ongoing + foreground-service + silent, which relay
 * apps (Zepp included) tend to swallow. We republish it as a normal, non-ongoing
 * notification from our own package so it takes the ordinary notification path.
 */
public final class Republisher {

    public static final int NOTIFICATION_ID = 1001;
    public static final String NOTIFICATION_TAG = "nav";

    /** Buzzes the phone and the watch; silent otherwise so it never fights Maps' voice. */
    static final String CHANNEL_RIDE = "nav_ride_v1";
    static final String CHANNEL_LOUD = "nav_loud";

    /** Rotating tag: every publish looks like a brand-new notification to relays. */
    private static String lastTag = null;
    private static int seq = 0;

    private Republisher() {}

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel ride = new NotificationChannel(
                CHANNEL_RIDE, "Navigation alerts", NotificationManager.IMPORTANCE_HIGH);
        ride.setDescription("Turn-by-turn instructions mirrored from Google Maps");
        ride.setSound(null, null);
        ride.enableVibration(true);
        ride.setVibrationPattern(new long[]{0, 300, 150, 300});
        ride.setShowBadge(false);
        ride.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ride);

        NotificationChannel loud = new NotificationChannel(
                CHANNEL_LOUD, "Navigation (sound)", NotificationManager.IMPORTANCE_HIGH);
        loud.setDescription("Same as silent, but audible on the phone");
        loud.enableVibration(true);
        loud.setShowBadge(false);
        loud.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(loud);
    }

    public static void publish(Context ctx, MapsParser.NavInfo info,
                               boolean alert, boolean loud) {
        publish(ctx, info, alert, loud, false);
    }

    /**
     * @param probe when true the notification gets its own tag and never cancels the live
     *              navigation card, so test pings survive an active route.
     */
    public static void publish(Context ctx, MapsParser.NavInfo info,
                               boolean alert, boolean loud, boolean probe) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannels(ctx);

        Maneuver.Kind kind = Maneuver.of(info.instruction);

        // The watch is round and only the first line renders in full, so the top line
        // carries arrow + distance and the body stays short (street name only).
        String title;
        String body;
        String head = info.distance.isEmpty()
                ? kind.glyph + " " + kind.label
                : kind.glyph + " " + info.distance;
        String road = shortRoad(info.instruction, kind);

        // Distance dominates the large top line; the street is the secondary line.
        // (A 15-char combined line fits, but the rider wants metres to rule the view.)
        title = head;
        body = road;

        Notification.Builder b = new Notification.Builder(ctx, loud ? CHANNEL_LOUD : CHANNEL_RIDE)
                .setSmallIcon(kind.iconRes)
                .setLargeIcon(renderArrow(ctx, kind.iconRes))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                // Deliberately NOT ongoing / not a foreground service, so relays forward it.
                .setCategory(Notification.CATEGORY_NAVIGATION)
                // Accent colour; also the tint some relays use for the watch card.
                .setColor(0xFF00FF00)
                .setAutoCancel(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(!alert);

        // A fresh tag/id each time: Zepp treats updates to an existing key as an update
        // and can swallow them, but a new key always gets relayed.
        String tag = "nav-" + (++seq);
        if (probe) {
            nm.notify("probe-" + seq, NOTIFICATION_ID + 7, b.build());
            NavState.published++;
            NavState.lastEvent = "probe #" + NavState.published;
            return;
        }
        nm.notify(tag, NOTIFICATION_ID, b.build());
        if (lastTag != null && !lastTag.equals(tag)) {
            nm.cancel(lastTag, NOTIFICATION_ID);
        }
        lastTag = tag;

        NavState.published++;
        NavState.lastEvent = "published #" + NavState.published + (alert ? " (alert)" : "");
    }

    /**
     * Pulls the road out of a guidance string: "Turn right onto Jl. Sudirman" ->
     * "Jl. Sudirman". Kept short because the round watch face clips long lines.
     */
    static String shortRoad(String instruction, Maneuver.Kind kind) {
        if (instruction == null || instruction.isEmpty()) return "Google Maps";
        String s = instruction.trim();
        String[] markers = {" onto ", " toward ", " towards ", " on ", " ke ", " menuju "};
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        for (String m : markers) {
            int i = lower.lastIndexOf(m);
            if (i >= 0 && i + m.length() < s.length()) {
                s = s.substring(i + m.length()).trim();
                break;
            }
        }
        if (s.equalsIgnoreCase(instruction.trim())) {
            // No road marker: the whole phrase is the instruction, keep it short.
            s = kind.label;
        }
        // "Jl. Sudirman" -> "Sudirman": the prefix costs screen space and adds nothing.
        s = s.replaceFirst("(?i)^(jl\\.?|jalan)\\s+", "");
        // Long names are fine; only trim what would run off the round display.
        return truncate(s, 24);
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max);
        if (cut < 8) cut = max;
        return s.substring(0, cut).trim() + "\u2026";
    }

    /** Rasterised arrow for the large-icon slot; some relays forward this as an image. */
    private static Bitmap renderArrow(Context ctx, int iconRes) {
        try {
            Drawable d = ctx.getDrawable(iconRes);
            if (d == null) return null;
            int size = 192;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Untouched text, for glyph/colour probes: no trimming, no formatting. */
    public static void publishRaw(Context ctx, String title, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannels(ctx);
        Notification n = new Notification.Builder(ctx, CHANNEL_RIDE)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setColor(0xFF00FF00)
                .build();
        nm.notify("probe-raw-" + (++seq), NOTIFICATION_ID + 9, n);
        NavState.lastEvent = "raw probe: " + title;
    }

    public static void cancel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
        if (lastTag != null) nm.cancel(lastTag, NOTIFICATION_ID);
        lastTag = null;
    }
}
