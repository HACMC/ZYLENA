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
     * The quote's words are nearly all those of one verse, or of two to four verses in a row, and the quote is about
     * as long as them: those verses from Ang Biblia, and where they are ("27:8:14"). A quote may leave out how the
     * first verse starts ("And he said unto me, Unto two thousand…"): the Tagalog then starts after as many commas.
     * A quote that leaves out parts in the middle or at the end is not matched (it is translated as usual).
     */
    @Override
    public String[] tagalogFor(String quote) {
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
        int bestFrom = -1, bestLen = 0, bestLead = 0;
        double bestScore = 0;
        java.util.Set<Long> tried = new java.util.HashSet<>();
        for (int t = 0; t < Math.min(8, top.size()); t++) {
            int c = top.get(t).getKey();
            for (int from = Math.max(0, c - 3); from <= c; from++) {
                for (int len = c - from + 1; len <= 4 && from + len <= order.length; len++) {
                    if (!tried.add(from * 10L + len)) continue;
                    if (order[from] / 1_000_000L != order[from + len - 1] / 1_000_000L) break; // one book
                    int lead = leadOf(words[from], q);
                    java.util.Set<String> span = new java.util.HashSet<>();
                    int spanCount = 0;
                    boolean each = true;
                    for (int k = from; k < from + len; k++) {
                        int start = k == from ? lead : 0, inQuote = 0;
                        for (int i = start; i < words[k].length; i++) {
                            if (qs.contains(words[k][i])) inQuote++;
                            span.add(words[k][i]);
                        }
                        int n = words[k].length - start;
                        if (n <= 0 || inQuote < n * 0.8) each = false; // every verse (from where the quote starts) nearly all in it
                        spanCount += Math.max(0, n);
                    }
                    if (!each || spanCount == 0) continue;
                    int found = 0;
                    for (String w : q) if (span.contains(w)) found++;
                    double share = found / (double) q.length, size = q.length / (double) spanCount;
                    if (share < 0.85 || size < 0.8 || size > 1.25) continue;
                    double score = share - Math.abs(1 - size) * 0.3;
                    if (score > bestScore) {
                        bestScore = score;
                        bestFrom = from;
                        bestLen = len;
                        bestLead = lead;
                    }
                }
            }
        }
        if (bestFrom < 0) return null;
        StringBuilder tlText = new StringBuilder();
        for (int k = bestFrom; k < bestFrom + bestLen; k++) {
            long key = order[k];
            int b = (int) (key / 1_000_000L), ch = (int) (key / 1000 % 1000), v = (int) (key % 1000);
            String t = get(true, b, ch, v);
            if (t == null) return null;
            if (k == bestFrom && bestLead > 1) {
                t = afterLead(get(false, b, ch, v), bestLead, t);
                if (t == null) return null; // can't tell where the quoted part starts in the Tagalog
            }
            if (tlText.length() > 0) tlText.append(' ');
            tlText.append(t);
        }
        long key = order[bestFrom];
        return new String[]{tlText.toString(), (key / 1_000_000L) + ":" + (key / 1000 % 1000) + ":" + (key % 1000) + (bestLen > 1 ? "+" + (bestLen - 1) : "")};
    }

    /** How many of the verse's words come before the quote's first two words (0 if they aren't found). */
    private static int leadOf(String[] verse, String[] q) {
        for (int i = 0; i + 1 < verse.length && i < verse.length / 2 + 1; i++) {
            if (verse[i].equals(q[0]) && verse[i + 1].equals(q[1])) return i;
        }
        return 0;
    }

    /**
     * The Tagalog verse from where the quote starts: the English words before it end at a comma, colon or
     * semicolon; the Tagalog is cut after as many of those. Null when they don't line up.
     */
    private static String afterLead(String en, int lead, String tl) {
        if (en == null) return null;
        java.util.regex.Matcher w = java.util.regex.Pattern.compile("[A-Za-z\u2019']+").matcher(en);
        int seen = 0, at = -1;
        while (w.find()) {
            if (w.group().replaceAll("[^A-Za-z]", "").length() < 3) continue;
            if (seen++ == lead) {
                at = w.start();
                break;
            }
        }
        if (at < 0) return null;
        String before = en.substring(0, at).trim();
        // Short words just before it ("…and said, O Daniel, I am now come") belong to the quote too.
        java.util.regex.Matcher tail = java.util.regex.Pattern.compile("[,;:](\\s+[A-Za-z]{1,2})+$").matcher(before);
        if (tail.find()) before = before.substring(0, tail.start() + 1);
        if (before.isEmpty() || ",;:".indexOf(before.charAt(before.length() - 1)) < 0) return null;
        at = before.length();
        int marks = before.replaceAll("[^,;:]", "").length();
        int cut = -1;
        for (int i = 0, n = 0; i < tl.length(); i++) {
            if (",;:".indexOf(tl.charAt(i)) >= 0 && ++n == marks) {
                cut = i;
                break;
            }
        }
        if (cut < 0) return null;
        // About the same share of the verse is left out in both.
        double enShare = at / (double) en.length(), tlShare = cut / (double) tl.length();
        if (Math.abs(enShare - tlShare) > 0.2) return null;
        String rest = tl.substring(cut + 1).trim();
        return rest.isEmpty() ? null : Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
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
