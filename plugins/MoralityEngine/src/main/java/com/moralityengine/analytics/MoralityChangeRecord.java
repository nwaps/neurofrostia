package com.moralityengine.analytics;

import java.util.UUID;

/**
 * Immutable snapshot of a single morality score change.
 * Serialized to/from a compact NDJSON line for the rolling analytics log.
 */
public record MoralityChangeRecord(
        UUID uuid,
        MoralityChangeReason reason,
        double delta,
        double newScore,
        long timestamp
) {

    // ── Serialization ─────────────────────────────────────────────────────────

    public String toJsonLine() {
        return String.format(
                "{\"u\":\"%s\",\"r\":\"%s\",\"d\":%.4f,\"s\":%.4f,\"t\":%d}",
                uuid, reason.name(), delta, newScore, timestamp
        );
    }

    /** Returns null if the line is malformed or contains an unrecognised reason. */
    public static MoralityChangeRecord fromJsonLine(String line) {
        try {
            String u = extractString(line, "u");
            String r = extractString(line, "r");
            double d = extractDouble(line, "d");
            double s = extractDouble(line, "s");
            long   t = extractLong  (line, "t");
            if (u == null || r == null) return null;
            return new MoralityChangeRecord(UUID.fromString(u), MoralityChangeReason.valueOf(r), d, s, t);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Simple field extractors (no external JSON library required) ───────────

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        return end == -1 ? null : json.substring(start, end);
    }

    private static double extractDouble(String json, String key) {
        int start = valueStart(json, key);
        if (start == -1) return 0.0;
        int end = start;
        while (end < json.length() && isNumericChar(json.charAt(end))) end++;
        return Double.parseDouble(json.substring(start, end));
    }

    private static long extractLong(String json, String key) {
        int start = valueStart(json, key);
        if (start == -1) return 0L;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Long.parseLong(json.substring(start, end));
    }

    private static int valueStart(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        return idx == -1 ? -1 : idx + search.length();
    }

    private static boolean isNumericChar(char c) {
        return Character.isDigit(c) || c == '.' || c == '-';
    }
}
