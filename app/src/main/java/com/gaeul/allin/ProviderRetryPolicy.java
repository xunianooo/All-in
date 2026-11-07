package com.gaeul.allin;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Validates an upstream Retry-After and returns bounded seconds, or null. */
public final class ProviderRetryPolicy {
    private static final long MAX_SECONDS = 6 * 60 * 60;
    private ProviderRetryPolicy() {}
    public static String safeRetryAfter(String header) { return safeRetryAfter(header, System.currentTimeMillis()); }
    static String safeRetryAfter(String header, long now) {
        if (header == null || header.length() > 128) return null;
        String s = header.trim();
        if (s.matches("[0-9]+")) {
            try { return Long.toString(Math.min(Long.parseLong(s), MAX_SECONDS)); }
            catch (NumberFormatException e) { return Long.toString(MAX_SECONDS); }
        }
        String[] formats = {"EEE, dd MMM yyyy HH:mm:ss 'GMT'", "EEEE, dd-MMM-yy HH:mm:ss 'GMT'", "EEE MMM d HH:mm:ss yyyy"};
        for (String format : formats) {
            SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
            parser.setLenient(false); parser.setTimeZone(TimeZone.getTimeZone("GMT"));
            ParsePosition pos = new ParsePosition(0);
            Date date = parser.parse(s, pos);
            if (date != null && pos.getIndex() == s.length()) {
                long delta = Math.max(0, date.getTime() - now);
                return Long.toString(Math.min(MAX_SECONDS, delta / 1000 + (delta % 1000 == 0 ? 0 : 1)));
            }
        }
        return null;
    }
}
