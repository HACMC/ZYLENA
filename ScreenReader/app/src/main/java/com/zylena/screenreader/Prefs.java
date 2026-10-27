package com.zylena.screenreader;

import android.content.Context;
import android.content.SharedPreferences;

/** All settings in one place. */
final class Prefs {
    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("zylena_reader", Context.MODE_PRIVATE);
    }

    /** 0.5 to 2.0, 1.0 = normal. */
    static float rate(Context c) {
        return sp(c).getFloat("rate", 1.0f);
    }

    static void setRate(Context c, float v) {
        sp(c).edit().putFloat("rate", v).apply();
    }

    /** Tagalog speed relative to the main speed (1.0 = the same). */
    static float rateFil(Context c) {
        return sp(c).getFloat("rate_fil", 1.0f);
    }

    static void setRateFil(Context c, float v) {
        sp(c).edit().putFloat("rate_fil", v).apply();
    }

    /** System voices only. 1.0 is the voice's own pitch, which sounds the most natural. */
    static float pitch(Context c) {
        return sp(c).getFloat("pitch", 1.0f);
    }

    static void setPitch(Context c, float v) {
        sp(c).edit().putFloat("pitch", v).apply();
    }

    /** Name of the chosen system voice for "en" or "fil"; empty = pick a female one automatically. */
    static String voice(Context c, String lang) {
        return sp(c).getString("voice_" + lang, "");
    }

    static void setVoice(Context c, String lang, String name) {
        sp(c).edit().putString("voice_" + lang, name).apply();
    }

    /** Package of the phone's text-to-speech app to use; empty = the phone's default. */
    static String ttsPackage(Context c) {
        return sp(c).getString("tts_pkg", "");
    }

    static void setTtsPackage(Context c, String v) {
        sp(c).edit().putString("tts_pkg", v).apply();
    }

    /** Read each paragraph translated: English in Tagalog, Tagalog in English. */
    static boolean translate(Context c) {
        return sp(c).getBoolean("translate", false);
    }

    static void setTranslate(Context c, boolean v) {
        sp(c).edit().putBoolean("translate", v).apply();
    }

    static boolean floatingButton(Context c) {
        return sp(c).getBoolean("floating", true);
    }

    static void setFloatingButton(Context c, boolean v) {
        sp(c).edit().putBoolean("floating", v).apply();
    }

    static boolean autoRead(Context c) {
        return sp(c).getBoolean("auto_read", false);
    }

    static void setAutoRead(Context c, boolean v) {
        sp(c).edit().putBoolean("auto_read", v).apply();
    }

    static int buttonX(Context c) {
        return sp(c).getInt("btn_x", -1);
    }

    static int buttonY(Context c) {
        return sp(c).getInt("btn_y", -1);
    }

    static void setButtonPos(Context c, int x, int y) {
        sp(c).edit().putInt("btn_x", x).putInt("btn_y", y).apply();
    }

    /** ZYLENA's service was running; if it is off when the app opens, the phone stopped it. */
    static boolean serviceWasOn(Context c) {
        return sp(c).getBoolean("service_was_on", false);
    }

    static void setServiceWasOn(Context c, boolean v) {
        sp(c).edit().putBoolean("service_was_on", v).apply();
    }

    /** Use the better online voice and translation when the internet works. */
    static boolean useInternet(Context c) {
        return sp(c).getBoolean("use_internet", true);
    }

    static void setUseInternet(Context c, boolean v) {
        sp(c).edit().putBoolean("use_internet", v).apply();
    }

    /** Data used this month under key ("data_tr", "data_voice"); starts again each month. */
    static long data(Context c, String key) {
        String month = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(new java.util.Date());
        return month.equals(sp(c).getString("data_month", "")) ? sp(c).getLong(key, 0) : 0;
    }

    static synchronized void addData(Context c, String key, long bytes) {
        String month = new java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(new java.util.Date());
        SharedPreferences.Editor e = sp(c).edit();
        if (!month.equals(sp(c).getString("data_month", ""))) {
            e.putString("data_month", month).putLong("data_tr", 0).putLong("data_voice", 0);
            e.putLong(key, bytes);
        } else {
            e.putLong(key, sp(c).getLong(key, 0) + bytes);
        }
        e.apply();
    }

    /** Every setting back to how it came, except this month's data count. */
    static void restoreDefaults(Context c) {
        SharedPreferences p = sp(c);
        String month = p.getString("data_month", "");
        long tr = p.getLong("data_tr", 0), voice = p.getLong("data_voice", 0);
        p.edit().clear().putString("data_month", month).putLong("data_tr", tr).putLong("data_voice", voice).commit();
    }

    /** The floating button's size: 0 small, 1 medium, 2 large, 3 extra large. */
    static int buttonSize(Context c) {
        return sp(c).getInt("btn_size", 1);
    }

    static void setButtonSize(Context c, int v) {
        sp(c).edit().putInt("btn_size", v).apply();
    }

    /** Leave out "2h ago", "10:42 PM", "Yesterday" and similar. */
    static boolean skipTimestamps(Context c) {
        return sp(c).getBoolean("skip_times", true);
    }

    static void setSkipTimestamps(Context c, boolean v) {
        sp(c).edit().putBoolean("skip_times", v).apply();
    }

    /**
     * What to do with Bible references: "skip" leaves them out ("...Apostol (Lucas 8:51). Matapos..." is read
     * "...Apostol. Matapos..."), "say" reads them ("Lucas kabanata 8, talata 51"), "verses" reads the verse
     * itself from the built-in Bible. One setting, so the choices can't cancel each other out.
     */
    static String refMode(Context c) {
        return sp(c).getString("ref_mode", "skip");
    }

    static void setRefMode(Context c, String v) {
        sp(c).edit().putString("ref_mode", v).apply();
    }

    static boolean skipRefs(Context c) {
        return refMode(c).equals("skip");
    }

    static boolean readVerses(Context c) {
        return refMode(c).equals("verses");
    }

    /** At the very bottom, turn to the next page (next day's lesson) and keep reading. */
    static boolean nextPage(Context c) {
        return sp(c).getBoolean("next_page", true);
    }

    static void setNextPage(Context c, boolean v) {
        sp(c).edit().putBoolean("next_page", v).apply();
    }

    /** The app that "Open & read" opens (Sabbath School unless the user picked another, e.g. Wattpad). */
    static String readApp(Context c) {
        return sp(c).getString("read_app", "com.cryart.sabbathschool");
    }

    static void setReadApp(Context c, String pkg) {
        sp(c).edit().putString("read_app", pkg).apply();
    }

    /** Leave out button names, icons and short tappable labels. */
    static boolean skipButtons(Context c) {
        return sp(c).getBoolean("skip_buttons", true);
    }

    static void setSkipButtons(Context c, boolean v) {
        sp(c).edit().putBoolean("skip_buttons", v).apply();
    }

    /** At the end of the screen, scroll down by itself and keep reading until the page ends. */
    static boolean wholePage(Context c) {
        return sp(c).getBoolean("whole_page", true);
    }

    static void setWholePage(Context c, boolean v) {
        sp(c).edit().putBoolean("whole_page", v).apply();
    }

    /** Gold outline on screen around the paragraph being read. */
    static boolean highlight(Context c) {
        return sp(c).getBoolean("highlight", true);
    }

    static void setHighlight(Context c, boolean v) {
        sp(c).edit().putBoolean("highlight", v).apply();
    }

    /** Back / Pause / Skip / Stop in the notification while reading. */
    static boolean notificationControls(Context c) {
        return sp(c).getBoolean("notif_controls", true);
    }

    static void setNotificationControls(Context c, boolean v) {
        sp(c).edit().putBoolean("notif_controls", v).apply();
    }

    /** Shake the phone twice to stop reading. */
    static boolean shakeStop(Context c) {
        return sp(c).getBoolean("shake_stop", false);
    }

    static void setShakeStop(Context c, boolean v) {
        sp(c).edit().putBoolean("shake_stop", v).apply();
    }

    /** The user's pronunciations, one "word = how to say it" per line. */
    static String sayList(Context c) {
        return sp(c).getString("say_list", "");
    }

    /** Words or phrases never to read, one per line. */
    static String skipList(Context c) {
        return sp(c).getString("skip_list", "");
    }

    static void setLists(Context c, String say, String skip) {
        sp(c).edit().putString("say_list", say).putString("skip_list", skip).apply();
        applyLists(c);
    }

    /** Hands the saved lists to the text preparation. */
    static void applyLists(Context c) {
        TextPrep.userSay = TextPrep.parseSay(sayList(c));
        TextPrep.userSkip = TextPrep.parseSkip(skipList(c));
    }

    /**
     * Only for the emulator test on GitHub (set by the test, no switch in the app): speech is timed
     * instead of spoken, and the log also goes to logcat.
     */
    static boolean testMode(Context c) {
        return sp(c).getBoolean("test_mode", false);
    }

    /** Reading a screen again continues from where it was stopped or left, when that text is still there. */
    static boolean resume(Context c) {
        return sp(c).getBoolean("resume", true);
    }

    static void setResume(Context c, boolean v) {
        sp(c).edit().putBoolean("resume", v).apply();
    }
}
