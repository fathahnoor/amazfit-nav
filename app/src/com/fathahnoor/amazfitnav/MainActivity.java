package com.fathahnoor.amazfitnav;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Diagnostics + switches. No external dependencies: builds fully offline. */
public class MainActivity extends Activity {

    private TextView status;
    private TextView dump;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
        Republisher.ensureChannels(this);
        handleReplay(getIntent());

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(heading("AmazfitNav"));
        root.addView(caption("Mirrors Google Maps turn-by-turn to the watch via Zepp."));

        status = new TextView(this);
        status.setTextSize(14f);
        status.setPadding(0, pad, 0, pad);
        root.addView(status);

        root.addView(button("1. Grant notification access", v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            NavState.lastEvent = "opened notification-access settings";
        }));

        root.addView(button("2. Send probe notification", v -> {
            NavListenerService.probe(this, "Turn right onto Jl. Sudirman", "200m");
            NavState.lastEvent = "probe posted";
        }));

        root.addView(button("2b. Cycle all maneuver arrows (watch check)", v -> {
            demoArrows();
            NavState.lastEvent = "cycling maneuver arrows on the watch";
        }));

        root.addView(button("3. Toggle media-session bridge", v -> {
            NavState.mediaBridgeOn = !NavState.mediaBridgeOn;
            if (!NavState.mediaBridgeOn) MediaBridge.stop();
            NavState.lastEvent = "media bridge "
                    + (NavState.mediaBridgeOn ? "on" : "off");
            NavState.rawDump = NavState.rawDump; // no-op, keeps state consistent
        }));

        root.addView(button("4. Toggle phone sound", v ->
                NavState.loudChannel = !NavState.loudChannel));

        root.addView(button("6. Test with 3-second warning", v -> {
            // Counts down on-screen first, so there is time to look at the watch.
            for (int s = 3; s >= 1; s--) {
                final int left = s;
                ui.postDelayed(() -> android.widget.Toast
                        .makeText(this, "Notification in " + left + "...", android.widget.Toast.LENGTH_SHORT)
                        .show(), (3 - s) * 1000L);
            }
            ui.postDelayed(() -> NavListenerService.probe(this,
                    "Turn right onto Jl. Sudirman", "200m"), 3000L);
            NavState.lastEvent = "3s warning test fired";
        }));

        root.addView(button("7. Test arrow glyphs (which render?)", v -> {
            ui.postDelayed(() -> NavListenerService.probe(this,
                    "A: \u2190 \u2192 \u2191 \u2193 B: \u2197 \u2196 \u2198 \u2199 C: \u21b0 \u21b1 \u21aa \u21a9 D: \u2b06 \u2b05 \u27a1 E: \u25c0 \u25b6 \u25b2 \u25bc",
                    "200 m"), 3000L);
            NavState.lastEvent = "glyph test fired";
        }));

        root.addView(button("8. Test colour (emoji swatches)", v -> {
            ui.postDelayed(() -> Republisher.publishRaw(this,
                    "\ud83d\udfe2 \ud83d\udfe9 \u2705 \ud83d\udfe5 \ud83d\udfe1", "swatch test"), 3000L);
            NavState.lastEvent = "colour test fired";
        }));

        root.addView(button("9. Test emoji arrows", v -> {
            ui.postDelayed(() -> Republisher.publishRaw(this,
                    "\u27a1\ufe0f \u2b05\ufe0f \u2b06\ufe0f \u2197\ufe0f \u21a9\ufe0f \ud83d\udd04 \ud83c\udfc1",
                    "emoji arrow test"), 3000L);
            NavState.lastEvent = "emoji arrow test fired";
        }));

        root.addView(button("5. Clear mirrored notification", v -> {
            Republisher.cancel(this);
            NavState.lastEvent = "mirrored notification cleared";
        }));

        dump = new TextView(this);
        dump.setTextSize(11f);
        dump.setTextColor(Color.DKGRAY);
        dump.setPadding(0, pad, 0, 0);
        root.addView(dump);

        setContentView(scroll);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleReplay(intent);
    }

    /**
     * adb-driven replay (tools/mcp_watch.py):
     *   am start -n com.fathahnoor.amazfitnav/.MainActivity \
     *       --es replay "Turn right onto Jl. Sudirman" --es distance "200m"
     * Goes through the same formatter and channel as live navigation, then closes, so
     * the watch payload can be checked without driving a route.
     */
    private void handleReplay(Intent i) {
        if (i == null) return;
        String instruction = i.getStringExtra("replay");
        if (instruction == null) return;
        String distance = i.getStringExtra("distance");
        android.util.Log.i("AmazfitNav", "replay: " + instruction + " / " + distance);
        try {
            NavListenerService.probe(this, instruction, distance == null ? "" : distance);
        } finally {
            i.removeExtra("replay");
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }

    private void render() {
        String listener = isListenerEnabled() ? "GRANTED" : "NOT granted";
        AudioManager am = getSystemService(AudioManager.class);
        boolean music = am != null && am.isMusicActive();

        StringBuilder sb = new StringBuilder();
        sb.append("notification access : ").append(listener).append('\n');
        sb.append("listener connected  : ").append(NavState.listenerConnected).append('\n');
        sb.append("isMusicActive()     : ").append(music).append('\n');
        sb.append("media bridge        : ").append(NavState.mediaBridgeOn ? "on" : "off").append('\n');
        sb.append("phone sound         : ").append(NavState.loudChannel ? "on" : "off").append('\n');
        sb.append("published           : ").append(NavState.published).append('\n');
        sb.append("last event          : ").append(NavState.lastEvent).append('\n');
        sb.append('\n');
        sb.append("maps instruction    : ").append(orDash(NavState.instruction)).append('\n');
        sb.append("distance            : ").append(orDash(NavState.distance)).append('\n');
        sb.append("eta                 : ").append(orDash(NavState.eta));
        status.setText(sb.toString());

        String d = NavState.rawDump;
        if (d != null && !d.isEmpty()) {
            dump.setText(d.length() > 4000 ? d.substring(0, 4000) + "\n..." : d);
        }
    }

    /** Posts each maneuver in turn so the arrows can be verified on the watch. */
    private void demoArrows() {
        String[][] demo = {
                {"Turn right onto Jl. Sudirman", "200 m", "right"},
                {"Turn left onto Jl. Thamrin", "150 m", "left"},
                {"Turn slightly right onto Jl. Imam Bonjol", "90 m", "slight right"},
                {"Turn slightly left onto Jl. Diponegoro", "60 m", "slight left"},
                {"Make a U-turn", "40 m", "u-turn"},
                {"At the roundabout, take the 2nd exit", "120 m", "roundabout"},
                {"Continue straight on Jl. Gatot Subroto", "800 m", "straight"},
                {"You have arrived at your destination", "", "arrive"},
        };
        for (int i = 0; i < demo.length; i++) {
            final String instruction = demo[i][0];
            final String distance = demo[i][1];
            // 12 s apart: fast enough to demo, slow enough that nothing is missed and
            // the BLE relay has time to deliver each one.
            ui.postDelayed(() -> NavListenerService.probe(this, instruction, distance),
                    i * 12_000L);
        }
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "\u2014" : s;
    }

    private boolean isListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private TextView heading(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(24f);
        tv.setGravity(Gravity.START);
        return tv;
    }

    private TextView caption(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        return tv;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(8 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        return b;
    }
}
