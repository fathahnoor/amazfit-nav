package com.fathahnoor.amazfitnav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

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

    static final String CHANNEL_QUIET = "nav_quiet";
    static final String CHANNEL_LOUD = "nav_loud";

    private Republisher() {}

    public static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel quiet = new NotificationChannel(
                CHANNEL_QUIET, "Navigation (silent)", NotificationManager.IMPORTANCE_HIGH);
        quiet.setDescription("Turn-by-turn instructions mirrored from Google Maps");
        quiet.setSound(null, null);
        quiet.enableVibration(false);
        quiet.setShowBadge(false);
        quiet.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(quiet);

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
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannels(ctx);

        String title = info.instruction.isEmpty() ? "Navigating" : info.instruction;
        String body;
        if (!info.distance.isEmpty() && !info.eta.isEmpty()) body = info.distance + "  \u00b7  " + info.eta;
        else if (!info.distance.isEmpty()) body = info.distance;
        else if (!info.eta.isEmpty()) body = info.eta;
        else body = "Google Maps";

        Notification.Builder b = new Notification.Builder(ctx, loud ? CHANNEL_LOUD : CHANNEL_QUIET)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                // Deliberately NOT ongoing / not a foreground service, so relays forward it.
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(!alert);

        nm.notify(NOTIFICATION_TAG, NOTIFICATION_ID, b.build());
        NavState.published++;
        NavState.lastEvent = "published #" + NavState.published + (alert ? " (alert)" : "");
    }

    public static void cancel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
    }
}
