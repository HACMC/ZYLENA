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

    // ---- Finding a quoted verse by its words ----

    private long[] order;          // every English verse's key, in Bible order
    private String[][] words;      // each verse's words (3+ letters), lower case
    private Map<String, int[]> index; // a word (4+ letters) -> the verses that have it

    private static final java.util.Set<String> COMMON = new java.util.HashSet<>(java.util.Arrays.asList(
            "that", "with", "unto", "they", "them", "shall", "which", "have", "from", "upon", "will", "their", "this",
            "there", "were", "when", "said", "also", "into", "then", "what", "your", "been", "even", "come", "came", "made"));

    static String[] wordsOf(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String w : s.toLowerCase(java.util.Locale.ROOT).replace("\u2019", "'").split("[^a-z]+")) if (w.length() >= 3) out.add(w);
        return out.toArray(new String[0]);
    }

    private synchronized void buildIndex() {
        if (index != null) return;
        long t0 = System.currentTimeMillis();
        Map<Long, String> e = en != null ? en : (en = load("bible/en"));
        order = new long[e.size()];
        int n = 0;
        for (Long k : e.keySet()) order[n++] = k;
        java.util.Arrays.sort(order);
        words = new String[order.length][];
        Map<String, java.util.ArrayList<Integer>> tmp = new HashMap<>(20000);
        Map<String, String> same = new HashMap<>(20000); // one copy of each word: the index stays a few MB
        for (int i = 0; i < order.length; i++) {
            words[i] = wordsOf(e.get(order[i]));
            for (int j = 0; j < words[i].length; j++) {
                String c = same.putIfAbsent(words[i][j], words[i][j]);
                if (c != null) words[i][j] = c;
            }
            for (String w : new java.util.HashSet<>(java.util.Arrays.asList(words[i]))) {
                if (w.length() < 4 || COMMON.contains(w)) continue;
                java.util.ArrayList<Integer> l = tmp.get(w);
                if (l == null) tmp.put(w, l = new java.util.ArrayList<>());
                l.add(i);
            }
        }
        Map<String, int[]> idx = new HashMap<>(tmp.size() * 2);
        for (Map.Entry<String, java.util.ArrayList<Integer>> en2 : tmp.entrySet()) {
            int[] a = new int[en2.getValue().size()];
            for (int i = 0; i < a.length; i++) a[i] = en2.getValue().get(i);
            idx.put(en2.getKey(), a);
        }
        index = idx;
        Logs.d(ctx, "bible word index: " + idx.size() + " words in " + (System.currentTimeMillis() - t0) + " ms");
    }

    /**
     * The quote is taken from the King James Version: nearly all its words are in one verse or in up to four verses
     * in a row (it may leave words out, as EGW often does). Where it is ("27:8:14"), else null.
     */
    @Override
    public String verseOf(String quote) {
        String[] q = wordsOf(quote);
        if (q.length < 6) return null;
        buildIndex();
        java.util.Set<String> qs = new java.util.HashSet<>(java.util.Arrays.asList(q));
        Map<Integer, Integer> hits = new HashMap<>();
        for (String w : qs) {
            int[] p = index.get(w);
            if (p == null || p.length > 2500) continue;
            for (int i : p) hits.merge(i, 1, Integer::sum);
        }
        if (hits.isEmpty()) return null;
        java.util.List<Map.Entry<Integer, Integer>> top = new java.util.ArrayList<>(hits.entrySet());
        top.sort((x, y) -> y.getValue() - x.getValue());
        for (int t = 0; t < Math.min(6, top.size()); t++) {
            int c = top.get(t).getKey();
            for (int from = Math.max(0, c - 3); from <= c; from++) {
                for (int len = c - from + 1; len <= 4 && from + len <= order.length; len++) {
                    if (order[from] / 1_000_000L != order[from + len - 1] / 1_000_000L) break;
                    java.util.Set<String> span = new java.util.HashSet<>();
                    int spanCount = 0;
                    for (int k = from; k < from + len; k++) {
                        span.addAll(java.util.Arrays.asList(words[k]));
                        spanCount += words[k].length;
                    }
                    int found = 0;
                    for (String w : q) if (span.contains(w)) found++;
                    // Nearly every word is the Bible's, and the quote is no longer than the verses.
                    if (found >= q.length * 0.9 && q.length <= spanCount * 1.1) {
                        long key = order[from];
                        return (key / 1_000_000L) + ":" + (key / 1000 % 1000) + ":" + (key % 1000) + (len > 1 ? "+" + (len - 1) : "");
                    }
                }
            }
        }
        return null;
    }

    /** Loads both Bibles in the background so the first reference doesn't hold up reading. */
    void warmUp() {
        new Thread(() -> {
            get(true, 1, 1, 1);
            get(false, 1, 1, 1);
            buildIndex(); // for finding quoted verses in a translation
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
