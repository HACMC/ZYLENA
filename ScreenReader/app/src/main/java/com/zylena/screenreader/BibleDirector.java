package com.zylena.screenreader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Knows where in the Bible a line of the screen is (which book, chapter and verse of the built-in Ang Biblia or King James Version) and
 * who speaks in it, worked out for the whole chapter at once with everything the chapter says: a verse read alone ("Saying, Let us
 * alone…", "Na nagsasabi, Ang iyong nakikita…") does not say who speaks, the verses around it do. While one chapter is read, the
 * next is made ready in the background, so what comes next is lined up before it is reached: where it is, and who talks in it.
 */
final class BibleDirector {
    private BibleDirector() {
    }

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bible-director");
        t.setDaemon(true);
        return t;
    });

    /** Where the last line read is, for the log: "66:1:11 (Jesus)". */
    static volatile String lastPlace = "";

    // ------------------------------------------------------------------ finding a line in the Bible

    private static volatile Map<Integer, long[]> index; // first words of a verse -> the places that begin so
    private static volatile boolean indexing;

    private static final Pattern NUMBER = Pattern.compile("^\\s*\\d{1,3}\\s*[.)]?\\s+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Letters and digits only, lower case: the same for the screen's text and the Bible's, whatever the marks and spaces. */
    static String flat(String s) {
        return NON_WORD.matcher(s.toLowerCase(Locale.ROOT).replace('’', '\'')).replaceAll("");
    }

    private static int leadKey(String s) {
        String[] w = NON_WORD.split(s.toLowerCase(Locale.ROOT).replace('’', '\'').trim());
        StringBuilder k = new StringBuilder();
        for (int i = 0; i < w.length && i < 6; i++) k.append(w[i]).append(' ');
        return k.toString().hashCode();
    }

    private static long place(boolean tl, int book, int chapter, int verse) {
        return (tl ? 1L << 40 : 0L) | (book * 1_000_000L + chapter * 1000L + verse);
    }

    private static void buildIndex(Bible.Verses src) {
        Map<Integer, long[]> idx = new HashMap<>(80000);
        for (int lang = 0; lang < 2; lang++) {
            boolean tl = lang == 1;
            for (int b = 1; b <= 66; b++) {
                for (int c = 1; c <= 150; c++) {
                    if (src.get(tl, b, c, 1) == null) break;
                    for (int v = 1; v <= 180; v++) {
                        String t = src.get(tl, b, c, v);
                        if (t == null) break;
                        int k = leadKey(t);
                        long p = place(tl, b, c, v);
                        long[] old = idx.get(k);
                        if (old == null) {
                            idx.put(k, new long[]{p});
                        } else {
                            long[] n = java.util.Arrays.copyOf(old, old.length + 1);
                            n[old.length] = p;
                            idx.put(k, n);
                        }
                    }
                }
            }
        }
        index = idx;
    }

    /** The place of a line of the screen in the built-in Bible: {tl (0 or 1), book, chapter, verse}, or null. */
    static int[] locate(Bible.Verses src, String line) {
        Map<Integer, long[]> idx = index;
        if (idx == null) return null;
        String text = NUMBER.matcher(line).replaceFirst("").trim();
        if (text.length() < 12) return null;
        long[] cand = idx.get(leadKey(text));
        if (cand == null) return null;
        String want = flat(text);
        for (long p : cand) {
            boolean tl = (p >> 40) != 0;
            long q = p & ((1L << 40) - 1);
            int b = (int) (q / 1_000_000L), c = (int) (q / 1000 % 1000), v = (int) (q % 1000);
            String t = src.get(tl, b, c, v);
            if (t != null && flat(t).equals(want)) return new int[]{tl ? 1 : 0, b, c, v};
        }
        return null;
    }

    // ------------------------------------------------------------------ the plan of a chapter

    private static final Map<String, BibleCast.Result[]> PLANS = new LinkedHashMap<String, BibleCast.Result[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BibleCast.Result[]> e) {
            return size() > 8;
        }
    };

    private static String planKey(boolean tl, int book, int chapter) {
        return (tl ? "tl" : "en") + "|" + book + "|" + chapter;
    }

    /** Who speaks in each verse of the chapter (by verse number minus one); null if the chapter is not in the Bible. */
    static BibleCast.Result[] plan(Bible.Verses src, boolean tl, int book, int chapter) {
        String key = planKey(tl, book, chapter);
        synchronized (PLANS) {
            BibleCast.Result[] have = PLANS.get(key);
            if (have != null) return have;
        }
        List<String> verses = new ArrayList<>();
        int context = 0;
        // A little of the chapter before: it may say who is speaking when this one begins in the middle of the words.
        if (chapter > 1) {
            List<String> tail = new ArrayList<>();
            for (int v = 60; v >= 1 && tail.size() < 4; v--) {
                String t = src.get(tl, book, chapter - 1, v);
                if (t != null) tail.add(0, t);
            }
            verses.addAll(tail);
            context = tail.size();
        }
        for (int v = 1; v <= 200; v++) {
            String t = src.get(tl, book, chapter, v);
            if (t == null) break;
            verses.add(t);
        }
        if (verses.size() == context) return null;
        BibleCast.Result[] per = BibleCast.annotateChapter(verses, context);
        BibleCast.Result[] byVerse = java.util.Arrays.copyOfRange(per, context, per.length);
        synchronized (PLANS) {
            PLANS.put(key, byVerse);
        }
        return byVerse;
    }

    /** Lines up the chapter after this one in the background. */
    private static void prepareNext(Bible.Verses src, boolean tl, int book, int chapter) {
        final int nb, nc;
        if (src.get(tl, book, chapter + 1, 1) != null) {
            nb = book;
            nc = chapter + 1;
        } else if (book < 66) {
            nb = book + 1;
            nc = 1;
        } else {
            return;
        }
        if (synchronizedHas(planKey(tl, nb, nc))) return;
        WORK.execute(() -> {
            try {
                plan(src, tl, nb, nc);
            } catch (RuntimeException ignored) {
            }
        });
    }

    private static boolean synchronizedHas(String key) {
        synchronized (PLANS) {
            return PLANS.containsKey(key);
        }
    }

    // ------------------------------------------------------------------ the line

    /**
     * What the plan says of a line of the screen: null when the line is not a verse of the built-in Bible (it is worked out alone, as before);
     * a result with no codes when nobody speaks in it; else the words with their quotation marks and the voice of each.
     */
    static BibleCast.Result lineResult(String line) {
        Bible.Verses src = Bible.source;
        if (src == null) return null;
        if (index == null) {
            if (!indexing) {
                indexing = true;
                WORK.execute(() -> {
                    try {
                        buildIndex(src);
                    } catch (RuntimeException ignored) {
                    } finally {
                        indexing = false;
                    }
                });
            }
            return null; // the first line or two are read before the Bible is known: they are worked out alone
        }
        int[] at = locate(src, line);
        if (at == null) return null;
        boolean tl = at[0] == 1;
        BibleCast.Result[] plan = plan(src, tl, at[1], at[2]);
        if (plan == null || at[3] < 1 || at[3] > plan.length) return null;
        BibleCast.Result r = plan[at[3] - 1];
        if (r == null) return null;
        lastPlace = at[1] + ":" + at[2] + ":" + at[3] + (r.codes.length > 0 ? " (" + r.summary + ")" : " (no speech)");
        prepareNext(src, tl, at[1], at[2]);
        return r;
    }

    /** Forgets what was lined up (a different Bible is chosen, or a test begins). */
    static void reset() {
        synchronized (PLANS) {
            PLANS.clear();
        }
        index = null;
    }
}
