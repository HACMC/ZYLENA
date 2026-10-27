package com.zylena.screenreader;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The built-in Bibles, both public domain: Ang Biblia (Philippine Bible Society, 1905) for Tagalog and the
 * King James Version for English. Each is loaded on first use (about a second) and kept in memory.
 */
final class VerseStore implements Bible.Verses {
    private final Context ctx;
    private Map<Long, String> tl, en;

    VerseStore(Context c) {
        ctx = c.getApplicationContext();
    }

    @Override
    public synchronized String get(boolean tagalog, int book, int chapter, int verse) {
        Map<Long, String> m = tagalog ? (tl != null ? tl : (tl = load("bible/tl")))
                : (en != null ? en : (en = load("bible/en")));
        return m.get(key(book, chapter, verse));
    }

    /** Loads both Bibles in the background so the first reference doesn't hold up reading. */
    void warmUp() {
        new Thread(() -> {
            get(true, 1, 1, 1);
            get(false, 1, 1, 1);
        }, "zylena-bible").start();
    }

    private static long key(int b, int c, int v) {
        return (b * 1000L + c) * 1000L + v;
    }

    private Map<Long, String> load(String asset) {
        Map<Long, String> m = new HashMap<>(40000);
        long t0 = System.currentTimeMillis();
        // Stored gzip-compressed as ".dat": a ".gz" name gets renamed by Android's packaging and can't be found.
        java.io.InputStream raw;
        try {
            raw = new GZIPInputStream(ctx.getAssets().open(asset + ".dat"));
        } catch (Exception e) {
            try {
                raw = ctx.getAssets().open(asset + ".txt"); // in case a build unpacked it
            } catch (Exception e2) {
                Logs.d(ctx, "couldn't open " + asset + ": " + e);
                return m;
            }
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int a = line.indexOf('|'), b = line.indexOf('|', a + 1), c = line.indexOf('|', b + 1);
                if (a < 0 || b < 0 || c < 0) continue;
                m.put(key(Integer.parseInt(line.substring(0, a)), Integer.parseInt(line.substring(a + 1, b)),
                        Integer.parseInt(line.substring(b + 1, c))), line.substring(c + 1));
            }
        } catch (Exception e) {
            Logs.d(ctx, "couldn't load " + asset + ": " + e);
        }
        Logs.d(ctx, "loaded " + asset + ": " + m.size() + " verses in " + (System.currentTimeMillis() - t0) + " ms");
        return m;
    }
}
