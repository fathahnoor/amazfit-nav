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

    /**
     * Distance milestones that are worth a buzz on the wrist. Each crossing produces
     * exactly one alert, so a turn costs at most a handful of vibrations instead of
     * one per Maps notification update (Maps rewrites it every second while moving).
     */
    // Two mid-leg pings plus one per maneuver keeps the wrist informative without
    // turning the ride into a vibration storm.
    // One pre-turn ping. A single threshold cannot be skipped by coarse updates, so the
    // wrist buzzes exactly once before each maneuver plus once when it changes.
    private static final int[] ALERT_THRESHOLDS = {150};

    private String lastInstruction = "";
    private String lastBody = "";
    private int lastLevel = -2;
    /** Last non-bogus distance, used to reject Maps' transient "1 m" states. */
    private int lastGoodMeters = -1;

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

        // If navigation is already running (e.g. the app was restarted mid-ride), pick
        // up the current instruction immediately instead of waiting for the next turn.
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (MapsParser.isMapsNavigation(sbn)) onNotificationPosted(sbn);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not read active notifications", t);
        }
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

        // "Starting navigation…" is Maps' placeholder; buzzing the wrist for it is noise.
        if (info.instruction.toLowerCase(java.util.Locale.ROOT).startsWith("starting")) {
            return;
        }

        boolean instructionChanged = !info.instruction.equals(lastInstruction);
        int meters = info.distanceToTurnMeters;

        // Maps alternates the real leg with a degenerate one (43 km -> 15 m within
        // milliseconds). Distance to the turn can only fall smoothly while the
        // instruction stays the same, so anything wildly off is noise, not progress.
        if (!instructionChanged && lastGoodMeters > 0 && meters >= 0) {
            boolean implausible = meters < lastGoodMeters / 4
                    || meters > lastGoodMeters + 150;
            if (implausible) return;
        }

        int level = levelFor(meters);
        boolean closer = level > lastLevel;
        boolean bodyChanged = !info.watchBody().equals(lastBody);

        // New maneuver, or we just passed a distance milestone -> buzz.
        // Anything else is silent, otherwise the watch vibrates continuously.
        boolean publish = instructionChanged || closer;
        if (!publish) {
            if (!bodyChanged) return;
        }

        Republisher.publish(this, info, true, NavState.loudChannel);
        if (NavState.mediaBridgeOn) MediaBridge.publish(this, info);

        Log.i(TAG, "published: \"" + info.oneLine() + "\" level=" + lastLevel + "->" + level);
        lastInstruction = info.instruction;
        lastBody = info.watchBody();
        lastLevel = level;
        if (meters > 10) lastGoodMeters = meters;
    }

    /** How many distance milestones are still ahead; rises as the turn approaches. */
    private static int levelFor(int meters) {
        if (meters < 0) return -1;
        int level = 0;
        for (int threshold : ALERT_THRESHOLDS) {
            if (meters <= threshold) level++;
        }
        return level;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!MapsParser.isMapsNavigation(sbn)) return;
        // Navigation ended (or Maps was closed): clear the mirrored notification too.
        Republisher.cancel(this);
        MediaBridge.stop();
        lastInstruction = "";
        lastBody = "";
        lastLevel = -2;
        lastGoodMeters = -1;
        NavState.instruction = "";
        NavState.distance = "";
        NavState.eta = "";
        NavState.lastEvent = "navigation ended";
        Log.i(TAG, "navigation ended, mirrored notification cleared");
    }

    /** Lets the settings screen fire a probe notification through the same path. */
    public static void probe(android.content.Context ctx, String instruction, String distance) {
        MapsParser.NavInfo info = new MapsParser.NavInfo();
        info.instruction = instruction;
        info.distance = distance;
        Republisher.publish(ctx, info, true, NavState.loudChannel, true);
    }
}
