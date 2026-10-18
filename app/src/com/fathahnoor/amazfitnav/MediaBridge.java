package com.fathahnoor.amazfitnav;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;

/**
 * Fallback delivery path.
 *
 * Zepp keeps syncing *media* metadata to the watch even while it is filtering
 * notifications (its watch-side music widget), so a fake media session that
 * "plays" the next maneuver is a second way to get text onto the watch.
 */
public final class MediaBridge {

    private static MediaSession session;

    private MediaBridge() {}

    public static synchronized void publish(Context ctx, MapsParser.NavInfo info) {
        if (session == null) {
            session = new MediaSession(ctx.getApplicationContext(), "AmazfitNav");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        }
        if (!session.isActive()) session.setActive(true);

        String title = info.instruction.isEmpty() ? "Navigating" : info.instruction;
        String sub = info.distance.isEmpty() ? info.eta : (info.distance + " " + info.eta).trim();

        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, sub)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "AmazfitNav")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 0L)
                .build());

        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, SystemClock.elapsedRealtime(), 1.0f)
                .build());
    }

    public static synchronized void stop() {
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0, 0f).build());
            session.setActive(false);
        }
    }
}
