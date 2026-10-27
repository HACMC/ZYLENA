package com.zylena.screenreader;

import android.content.Context;

/**
 * Internet use. With "Use internet" on and a working connection, ZYLENA uses the online versions that
 * are better (Google's online voices, Google Translate online); otherwise everything stays on the phone.
 * Also keeps count of the data this uses, per month.
 */
final class Net {
    private Net() {
    }

    // Google doesn't say how much its online voices download; about 24 kbit/s of speech at ~14 letters
    // a second is ~215 bytes a letter, so roughly 11 MB an hour of listening.
    static final int VOICE_BYTES_PER_CHAR = 215;

    /** Use the online version now: allowed, and the internet really works (not just Wi-Fi without a connection). */
    static boolean online(Context c) {
        if (!Prefs.useInternet(c)) return false;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    static void translated(Context c, long bytes) {
        Prefs.addData(c, "data_tr", bytes);
    }

    static void spokeOnline(Context c, int chars) {
        Prefs.addData(c, "data_voice", (long) chars * VOICE_BYTES_PER_CHAR);
    }

    /** "Translation 120 KB · online voice about 4.2 MB" for this month. */
    static String usedThisMonth(Context c) {
        long tr = Prefs.data(c, "data_tr"), voice = Prefs.data(c, "data_voice");
        return "This month: about " + size(tr + voice) + " (voice " + size(voice) + ", translation " + size(tr) + ")";
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return Math.round(b / 1024.0) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }
}
