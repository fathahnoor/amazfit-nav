package com.fathahnoor.amazfitnav;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Understands Google Maps' turn-by-turn notification.
 *
 * Ground truth (captured from this phone, Android 16, Maps as of 2026-10):
 *   channel=1_foreground_1  category=navigation
 *   flags=ONGOING_EVENT|FOREGROUND_SERVICE|PROMOTED_ONGOING|SILENT
 *   android.title      -> maneuver  ("Head northwest on Jl. ...")
 *   android.subText    -> ETA       ("Arrive 11:26 PM")
 *   android.text/bigText -> null    (distance is *not* a plain string)
 *   android.progress / progressMax / progressSegments carry the route geometry
 */
public final class MapsParser {

    public static final String MAPS_PKG = "com.google.android.apps.maps";
    /** Maps' navigation channel. Also used as the "this is navigation" signal. */
    public static final String NAV_CHANNEL = "1_foreground_1";

    // ProgressStyle keys live in Notification.ProgressStyle; use literals so this
    // compiles against any android.jar and still reads them on-device.
    private static final String EXTRA_PROGRESS_SEGMENTS = "android.progressSegments";
    private static final String EXTRA_SEGMENT_NAME = "android.progressSegment.name";

    public static final class NavInfo {
        public String instruction = "";
        public String distance = "";
        public String eta = "";
        public String dump = "";
        /** Distance to the next maneuver (current leg remainder), meters; -1 unknown. */
        public int distanceToTurnMeters = -1;
        /** Distance still to go on the whole route, meters; -1 unknown. */
        public int totalRemainingMeters = -1;

        /** What the watch should show as the second line. */
        public String watchBody() {
            String d = distance.isEmpty() && distanceToTurnMeters >= 0
                    ? prettyDistance(distanceToTurnMeters) : distance;
            if (!d.isEmpty() && !eta.isEmpty()) return d + "  \u00b7  " + eta;
            if (!d.isEmpty()) return d;
            return eta;
        }

        public String oneLine() {
            if (instruction.isEmpty()) return "";
            String b = watchBody();
            return b.isEmpty() ? instruction : b + " \u00b7 " + instruction;
        }
    }

    private MapsParser() {}

    public static boolean isMapsNavigation(StatusBarNotification sbn) {
        if (sbn == null || !MAPS_PKG.equals(sbn.getPackageName())) return false;
        Notification n = sbn.getNotification();
        if (n == null) return false;
        if (NAV_CHANNEL.equals(n.getChannelId())) return true;
        return Notification.CATEGORY_NAVIGATION.equals(n.category);
    }

    public static NavInfo parse(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        NavInfo info = new NavInfo();
        if (n == null) return info;

        Bundle e = n.extras != null ? n.extras : new Bundle();
        info.instruction = text(e.getCharSequence(Notification.EXTRA_TITLE));
        info.eta = text(e.getCharSequence(Notification.EXTRA_SUB_TEXT));

        // Some Maps builds put the remaining distance in text/bigText instead.
        String plain = text(e.getCharSequence(Notification.EXTRA_TEXT));
        String big = text(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (plain.isEmpty()) plain = big;

        int progress = e.getInt(Notification.EXTRA_PROGRESS, 0);
        int progressMax = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0);

        // The route is split into legs; segment[0] is the leg we are on right now, so
        // (length - progress) is exactly "how far until the next turn".
        int legLength = firstSegmentLength(e);
        if (legLength > 0) {
            // Maps reports progress against the current leg (and, at nav start, a
            // meaningless 0). Only subtract it while it is actually inside the leg,
            // otherwise the first instruction reads "0 m".
            int covered = (progress > 0 && progress < legLength) ? progress : 0;
            info.distanceToTurnMeters = legLength - covered;
        }
        if (progressMax > 0) {
            int remaining = progressMax - progress;
            if (remaining > 0) info.totalRemainingMeters = remaining;
        }

        if (!plain.isEmpty() && looksLikeDistance(plain)) {
            info.distance = plain;
            if (info.distanceToTurnMeters < 0) info.distanceToTurnMeters = metersFrom(plain);
        }
        if (info.distance.isEmpty() && info.distanceToTurnMeters >= 0) {
            info.distance = prettyDistance(info.distanceToTurnMeters);
        } else if (info.distance.isEmpty() && info.totalRemainingMeters > 0) {
            info.distance = prettyDistance(info.totalRemainingMeters);
        }

        info.dump = dumpExtras(e);
        return info;
    }

    /**
     * ProgressStyle segments are Bundles of {length, colorInt, id, semanticStyle}.
     * Observed on-device 2026-10: 62434, 1485, 267, 1903, ... metres for a 165 km route.
     */
    private static int firstSegmentLength(Bundle e) {
        try {
            ArrayList<?> segs = e.getParcelableArrayList(EXTRA_PROGRESS_SEGMENTS);
            if (segs == null || segs.isEmpty()) return -1;
            Object first = segs.get(0);
            if (first instanceof Bundle) {
                int len = ((Bundle) first).getInt("length", -1);
                return len > 0 && len < 5_000_000 ? len : -1;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static boolean looksLikeDistance(String s) {
        String t = s.trim().toLowerCase();
        return t.matches(".*\\d[\\d.,]*\\s*(m|km|mi|ft|yd)\\b.*")
                || t.matches("^\\d[\\d.,]*\\s*(m|km|mi|ft|yd)$");
    }

    private static int metersFrom(String s) {
        try {
            String t = s.trim().toLowerCase().replace(",", ".");
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(km|m|mi|ft|yd)")
                            .matcher(t);
            if (!m.find()) return -1;
            double v = Double.parseDouble(m.group(1));
            switch (m.group(2)) {
                case "km": return (int) Math.round(v * 1000);
                case "mi": return (int) Math.round(v * 1609.34);
                case "ft": return (int) Math.round(v * 0.3048);
                case "yd": return (int) Math.round(v * 0.9144);
                default:   return (int) Math.round(v);
            }
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Compact distance for a 1.2" watch screen, with no inner spaces so the digits stay
     * as large as the firmware allows: "850m", "1.2km".
     */
    public static String prettyDistance(int meters) {
        if (meters < 0) return "";
        if (meters < 1000) {
            int rounded = meters <= 50 ? meters : (meters / 10) * 10;
            return rounded + "m";
        }
        if (meters >= 10_000) {
            // Precision is irrelevant far out and shorter text stays legible.
            return Math.round(meters / 1000.0) + "km";
        }
        return String.format(java.util.Locale.US, "%.1fkm", meters / 1000.0);
    }

    private static String text(CharSequence cs) {
        return cs == null ? "" : cs.toString().replace('\n', ' ').trim();
    }

    /** Full key/value dump; this is how the real ProgressStyle schema was learned. */
    public static String dumpExtras(Bundle e) {
        StringBuilder sb = new StringBuilder();
        Set<String> keys = e.keySet();
        List<String> sorted = new ArrayList<>(keys);
        java.util.Collections.sort(sorted);
        for (String k : sorted) {
            if ("android.reduced.images".equals(k)) continue;
            Object v = e.get(k);
            sb.append(k).append(" = ");
            if (v instanceof Object[]) {
                Object[] arr = (Object[]) v;
                sb.append('[').append(arr.length).append(" items]");
                for (int i = 0; i < Math.min(arr.length, 4); i++) {
                    sb.append("\n    #").append(i).append(' ').append(bundleToText(arr[i]));
                }
            } else if (v instanceof List) {
                List<?> list = (List<?>) v;
                sb.append('[').append(list.size()).append(" items]");
                for (int i = 0; i < Math.min(list.size(), 4); i++) {
                    sb.append("\n    #").append(i).append(' ').append(bundleToText(list.get(i)));
                }
            } else {
                sb.append(v);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String bundleToText(Object o) {
        if (o instanceof Bundle) {
            Bundle b = (Bundle) o;
            StringBuilder sb = new StringBuilder("{");
            for (String k : b.keySet()) {
                Object v = b.get(k);
                String name = k.replace("android.progressSegment.", "");
                sb.append(name).append('=').append(v).append(", ");
            }
            if (sb.length() > 1) sb.setLength(sb.length() - 2);
            return sb.append('}').toString();
        }
        return String.valueOf(o);
    }

    /** Road name of the current leg, used as an extra line on the watch. */
    public static String segmentName(Bundle e) {
        try {
            ArrayList<?> segs = e.getParcelableArrayList(EXTRA_PROGRESS_SEGMENTS);
            if (segs == null || segs.isEmpty()) return "";
            Object first = segs.get(0);
            if (first instanceof Bundle) {
                Object name = ((Bundle) first).get(EXTRA_SEGMENT_NAME);
                return name == null ? "" : name.toString();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
}
