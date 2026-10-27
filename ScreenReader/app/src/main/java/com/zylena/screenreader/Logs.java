package com.zylena.screenreader;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** A small on-device log (last ~100 KB) so problems can be read and shared from the app. */
final class Logs {
    private static final int MAX = 200_000, KEEP = 100_000;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Logs() {
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "reader-log.txt");
    }

    /** Set while the emulator test runs: also writes to logcat, where the test reads it. */
    static volatile boolean toLogcat;

    /** Extra detail that only the emulator test needs (outline positions and the like). */
    static void t(Context c, String msg) {
        if (toLogcat) d(c, "TEST " + msg);
    }

    static synchronized void d(Context c, String msg) {
        if (toLogcat) android.util.Log.i("ZYLENA", msg);
        try {
            File f = file(c);
            if (f.length() > MAX) {
                String all = read(c);
                String tail = all.substring(Math.max(0, all.length() - KEEP));
                int nl = tail.indexOf('\n');
                write(f, nl >= 0 ? tail.substring(nl + 1) : tail, false);
            }
            write(f, TIME.format(new Date()) + "  " + msg + "\n", true);
        } catch (Exception ignored) {
        }
    }

    private static void write(File f, String s, boolean append) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f, append)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    static synchronized String read(Context c) {
        try {
            File f = file(c);
            if (!f.exists()) return "";
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized void clear(Context c) {
        file(c).delete();
    }

    /** Short, single-line preview of screen text for the log. */
    static String snip(String s) {
        s = s.replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
