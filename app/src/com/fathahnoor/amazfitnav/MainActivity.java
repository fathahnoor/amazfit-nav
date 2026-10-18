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
            NavListenerService.probe(this, "Turn right onto Jl. Sudirman", "200 m");
            NavState.lastEvent = "probe posted";
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
