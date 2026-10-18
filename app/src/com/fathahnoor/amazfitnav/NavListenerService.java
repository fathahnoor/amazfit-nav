package com.fathahnoor.amazfitnav;

import android.app.Notification;
import android.content.Intent;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Watches the notification stream for Google Maps' navigation notification and
 * republishes each new instruction as an ordinary notification (and, optionally,
 * as fake media metadata) so Zepp can push it to the watch.
 */
public class NavListenerService extends NotificationListenerService {

    private static final String TAG = "AmazfitNav";
    private static final long MIN_UPDATE_GAP_MS = 4_000L;

    private String lastInstruction = "";
    private int lastMeters = -1;
    private long lastPostMs = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        Republisher.ensureChannels(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        NavState.listenerConnected = true;
        NavState.lastEvent = "listener connected";
        Log.i(TAG, "listener connected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        NavState.listenerConnected = false;
        NavState.lastEvent = "listener disconnected";
        Log.w(TAG, "listener disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!MapsParser.isMapsNavigation(sbn)) return;

        MapsParser.NavInfo info = MapsParser.parse(sbn);
        if (NavState.rawDump.isEmpty() || !info.instruction.equals(NavState.instruction)) {
            NavState.rawDump = info.dump;
            Log.i(TAG, "maps nav extras:\n" + info.dump);
        }
        NavState.instruction = info.instruction;
        NavState.distance = info.distance;
        NavState.eta = info.eta;
        NavState.lastUpdate = System.currentTimeMillis();

        long now = SystemClock.elapsedRealtime();
        int meters = info.distanceMeters;

        boolean instructionChanged = !info.instruction.equals(lastInstruction);
        boolean crossing =
                meters >= 0 && meters <= 200 && (lastMeters < 0 || meters / 50 != lastMeters / 50);
        boolean alert = instructionChanged || crossing;

        if (!alert && now - lastPostMs < MIN_UPDATE_GAP_MS) return;

        Republisher.publish(this, info, alert, NavState.loudChannel);
        if (NavState.mediaBridgeOn) MediaBridge.publish(this, info);

        Log.i(TAG, "published: \"" + info.oneLine() + "\" alert=" + alert);
        lastInstruction = info.instruction;
        lastMeters = meters;
        lastPostMs = now;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!MapsParser.isMapsNavigation(sbn)) return;
        // Navigation ended (or Maps was closed): clear the mirrored notification too.
        Republisher.cancel(this);
        MediaBridge.stop();
        lastInstruction = "";
        lastMeters = -1;
        lastPostMs = 0L;
        NavState.instruction = "";
        NavState.distance = "";
        NavState.eta = "";
        NavState.lastEvent = "navigation ended";
        Log.i(TAG, "navigation ended, mirrored notification cleared");
    }

    /** Lets the settings screen fire a probe notification through the same path. */
    public static void probe(android.content.Context ctx, String title, String body) {
        MapsParser.NavInfo info = new MapsParser.NavInfo();
        info.instruction = title;
        info.distance = body;
        Republisher.publish(ctx, info, true, NavState.loudChannel);
    }

    static Notification buildProbe(android.content.Context ctx, String title, String body) {
        return new Notification.Builder(ctx, Republisher.CHANNEL_QUIET)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title)
                .setContentText(body)
                .build();
    }
}
