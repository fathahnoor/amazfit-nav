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
    private static final String EXTRA_PROGRESS_POINTS = "android.progressPoints";
    private static final String EXTRA_SEGMENT_NAME = "android.progressSegment.name";

    public static final class NavInfo {
        public String instruction = "";
        public String distance = "";
        public String eta = "";
        public String dump = "";
        /** Remaining distance for the current leg, in meters, or -1 when unknown. */
        public int distanceMeters = -1;

        public String oneLine() {
            if (instruction.isEmpty()) return "";
            return distance.isEmpty() ? instruction : distance + " \u00b7 " + instruction;
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

        int progress = e.getInt(Notification.EXTRA_PROGRESS, -1);
        int progressMax = e.getInt(Notification.EXTRA_PROGRESS_MAX, -1);

        if (!plain.isEmpty() && looksLikeDistance(plain)) {
            info.distance = plain;
            info.distanceMeters = metersFrom(plain);
        } else if (progress >= 0 && progressMax > 0 && progressMax != 1) {
            int remaining = progressMax - progress;
            // Maps has shipped both meters and centimetres here; ignore absurd values.
            if (remaining >= 0 && remaining < 5_000_000) {
                info.distanceMeters = remaining;
                info.distance = prettyDistance(remaining);
            }
        }

        if (info.distance.isEmpty()) {
            int fromSegments = distanceFromSegments(e, progress);
            if (fromSegments > 0) {
                info.distanceMeters = fromSegments;
                info.distance = prettyDistance(fromSegments);
            }
        }

        info.dump = dumpExtras(e);
        return info;
    }

    /**
     * The route is described by ProgressStyle segments (one per leg). When they carry
     * position information we can subtract the current progress to get the distance to
     * the next leg boundary.
     */
    private static int distanceFromSegments(Bundle e, int progress) {
        try {
            ArrayList<?> segs = e.getParcelableArrayList(EXTRA_PROGRESS_SEGMENTS);
            if (segs == null || segs.size() < 2) return -1;
            long[] points = e.getLongArray(EXTRA_PROGRESS_POINTS);
            if (points == null || points.length < 2) return -1;
            long next = points[1];
            long now = progress >= 0 ? progress : points[0];
            long delta = next - now;
            return delta > 0 && delta < 5_000_000 ? (int) delta : -1;
        } catch (Throwable ignored) {
            return -1;
        }
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

    /** Compact distance for a 1.2" watch screen: "850 m", "1.2 km". */
    public static String prettyDistance(int meters) {
        if (meters < 0) return "";
        if (meters < 1000) {
            int rounded = meters <= 50 ? meters : (meters / 10) * 10;
            return rounded + " m";
        }
        double km = meters / 1000.0;
        return String.format(java.util.Locale.US, "%.1f km", km);
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
