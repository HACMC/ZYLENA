package com.zylena.screenreader;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Character voices made by processing the sound: a child's, an old person's, a husky one, a mob boss's, a maid's.
 * The phone's voice first says the words into a file; here the recording is made higher or lower (and quicker or
 * slower), given a shake, some breath or some grit, and ZYLENA plays it. It works with any voice on the phone,
 * including the online ones that ignore a request for another pitch.
 */
final class VoiceFx {
    private VoiceFx() {
    }

    static final class Style {
        final String name;
        /** Pitch: how much higher (above 1) or lower the voice is. */
        final float pf;
        /** Tempo: how much quicker or slower it is said, in the end. */
        final float tf;
        /** Wobble in the pitch (an old voice's tremble), as a share of the pitch. */
        final float vib;
        /** Breath in the voice: husky, hoarse. */
        final float husk;
        /** Grit: the sound is pushed until it roughens. */
        final float drive;
        /** Loudness. */
        final float vol;
        /** Muffles the top of the sound (a heavy, rumbling voice). */
        final boolean dull;
        /** How loud the sound is made (speech is 0.10 to 0.20), and some extra upper-middle range for clarity (0 to 1). */
        float level = 0.17f, presence = 0f;
        /** Warmth: more of the low end (0 to 1). Air: more of the top, for a light, soft voice (0 to 1). */
        float warm = 0f, air = 0f;
        /** The room around the voice: how much of it is heard (0 none, 0.25 a great hall) and how long it rings, in seconds. */
        float space = 0f, room = 1f;
        /** Hiss: the "s" sounds made sharper and louder, like a snake's (0 none, 1 strong). */
        float hiss = 0f;

        Style(String name, float pf, float tf, float vib, float husk, float drive, float vol, boolean dull) {
            this.name = name;
            this.pf = pf;
            this.tf = tf;
            this.vib = vib;
            this.husk = husk;
            this.drive = drive;
            this.vol = vol;
            this.dull = dull;
        }
    }

    private static final Map<String, Style> STYLES = new HashMap<>();

    private static void add(String name, float pf, float tf, float vib, float husk, float drive, float vol, boolean dull) {
        STYLES.put(name, new Style(name, pf, tf, vib, husk, drive, vol, dull));
    }

    /** A voice of the Father, the Son or the Holy Spirit: pitch and tempo, then its colour (clarity, warmth, air) and the room it is heard in. */
    private static void divine(String name, float pf, float tf, float vol, float level, float presence, float warm, float air, float space, float room) {
        add(name, pf, tf, 0f, 0f, 0f, vol, false);
        Style st = STYLES.get(name);
        st.level = level;
        st.presence = presence;
        st.warm = warm;
        st.air = air;
        st.space = space;
        st.room = room;
    }

    static {
        // The three are told apart by more than pitch, since a phone often has one man's voice for all of them (in Tagalog
        // nearly always): each has a pitch, a colour and a room of its own.
        // The Father: majestic and calm (a voice "as the sound of many waters"): deep, slow, full, and heard as in a great hall.
        // In Tagalog the phone's voices are plainer, and the more the sound is changed the less natural it gets: so each is
        // changed less there (less lowering, a smaller room), and told apart more by pace.
        divine("deep", 0.86f, 0.88f, 1f, 0.18f, 0.05f, 0.45f, 0f, 0.20f, 1.5f);
        divine("deep_fil", 0.88f, 0.90f, 1f, 0.15f, 0.20f, 0.40f, 0.10f, 0.20f, 1.5f);
        // The Son took our nature, so His is a man's own voice and not the Father's made milder: natural in pitch, in the
        // open air beside the hearer (no hall around it). What sets it apart is how He spoke: "musical", "never raised in
        // high, strained notes", not "so rapidly that His words were crowded one upon another", "He distinctly enunciated
        // every word" (Ellen White), "clear", calm and earnest; "He shall not cry, nor lift up" (Isaiah 42:2). So: unhurried,
        // very clear, a little warm and bright, even in loudness.
        divine("jesus", 0.98f, 0.92f, 1f, 0.20f, 0.22f, 0.22f, 0.10f, 0.03f, 0.35f);
        // In Tagalog the Son and the Holy Spirit share one voice (the Spirit speaks in only a few verses): clean and natural, as chosen by ear.
        divine("jesus_fil", 0.99f, 0.98f, 1f, 0.10f, 0.15f, 0.20f, 0.08f, 0.10f, 1.0f);
        // The Holy Spirit: soft, "a still small voice" (kept audible): a little lighter than the plain voice, slow, airy, with a soft glow around it.
        divine("spirit", 1.04f, 0.90f, 0.95f, 0.15f, 0f, 0f, 0.35f, 0.16f, 1.1f);
        divine("spirit_fil", 0.99f, 0.98f, 1f, 0.10f, 0.15f, 0.20f, 0.08f, 0.10f, 1.0f);
        add("child", 1.30f, 1.05f, 0f, 0f, 0f, 1f, false);
        add("squeaky", 1.38f, 1.08f, 0f, 0f, 0f, 1f, false);
        add("cute", 1.18f, 1.04f, 0f, 0f, 0f, 1f, false);
        add("teen", 1.07f, 1.02f, 0f, 0f, 0f, 1f, false);
        add("elder", 0.90f, 0.88f, 0.018f, 0.08f, 0f, 0.95f, false);
        add("husky", 0.93f, 0.95f, 0f, 0.22f, 0.08f, 1f, false);
        add("boss", 0.80f, 0.86f, 0f, 0.16f, 0.30f, 1f, true); // a mafia boss: low, slow, gravelly
        add("gruff", 0.86f, 0.93f, 0f, 0.22f, 0.25f, 1f, true);
        add("bass", 0.84f, 0.92f, 0f, 0.05f, 0.05f, 1f, true);
        // The tempter, Satan, a demon, the serpent: very low and slow, with a growl, a dark room around it and a snake's hiss on the "s".
        add("villain", 0.78f, 0.86f, 0.004f, 0.10f, 0.40f, 1f, true);
        Style v = STYLES.get("villain");
        v.level = 0.19f;
        v.presence = 0.30f;
        v.warm = 0.45f;
        v.space = 0.22f;
        v.room = 1.8f;
        v.hiss = 0.9f;
        // Unclean spirits, devils, Legion: lower still and rougher than the tempter, torn and crowded, crying out rather than smooth.
        add("demon", 0.74f, 0.94f, 0.006f, 0.30f, 0.60f, 1f, true);
        Style dm = STYLES.get("demon");
        dm.level = 0.18f;
        dm.presence = 0.28f;
        dm.warm = 0.30f;
        dm.space = 0.28f;
        dm.room = 2.0f;
        add("maid", 1.08f, 0.98f, 0f, 0.03f, 0f, 0.92f, false); // polite, light
        add("butler", 0.94f, 0.94f, 0f, 0.02f, 0f, 0.95f, false);
        add("maarte", 1.10f, 0.95f, 0f, 0f, 0f, 1f, false); // affected, "sosyal": a little higher and slower, with care
        add("gentle", 1.05f, 0.95f, 0f, 0.07f, 0f, 0.85f, false);
    }

    private static void feel(String code, float pf, float tf, float vib, float husk, float drive, float vol, float level) {
        add("feel_" + code, pf, tf, vib, husk, drive, vol, false);
        STYLES.get("feel_" + code).level = level;
    }

    static {
        // How a line is said, made audible by changing the sound itself (an online voice often ignores a change of pitch).
        feel("s", 1.10f, 1.10f, 0f, 0f, 0.12f, 1.0f, 0.24f); // shouted
        feel("a", 0.93f, 1.06f, 0f, 0.04f, 0.22f, 1.0f, 0.21f); // angry
        feel("d", 0.94f, 0.85f, 0.01f, 0.10f, 0f, 0.85f, 0.13f); // sad
        feel("f", 1.12f, 1.12f, 0.02f, 0.08f, 0f, 0.9f, 0.15f); // afraid
        feel("l", 1.12f, 1.10f, 0f, 0f, 0f, 1.0f, 0.19f); // laughing while speaking
        feel("j", 1.08f, 1.06f, 0f, 0f, 0f, 1.0f, 0.18f); // glad
        feel("w", 1.0f, 0.90f, 0f, 0.38f, 0f, 0.65f, 0.07f); // whispered
        feel("t", 0.97f, 0.88f, 0f, 0.12f, 0f, 0.85f, 0.12f); // tender
        feel("p", 1.06f, 0.90f, 0.02f, 0.08f, 0f, 0.9f, 0.14f); // pleading
        feel("m", 0.95f, 0.88f, 0.004f, 0.08f, 0.05f, 0.92f, 0.17f); // sly and smooth, drawn out (the tempter)
        feel("r",1.07f, 1.22f, 0f, 0f, 0f, 1.0f, 0.18f); // rambling: fast and rushed
        feel("h", 0.92f, 0.80f, 0f, 0.25f, 0f, 0.8f, 0.10f); // a sigh
    }

    /** The voice of a feeling ("s" shouted, "a" angry, "d" sad, …), or null. */
    static Style forFeeling(String code) {
        return code == null || code.isEmpty() ? null : STYLES.get("feel_" + code);
    }

    /**
     * A kind of voice (or none) with a feeling on top, the feeling at the strength k (the user's tone strength): the pitches
     * and tempos multiply, the roughness and breath take the larger, the loudness is brought towards the feeling's.
     */
    static Style combine(Style kind, Style feeling, float k) {
        if (feeling == null) return kind;
        Style f = new Style("feeling", 1f + (feeling.pf - 1f) * k, 1f + (feeling.tf - 1f) * k, feeling.vib * k, feeling.husk * k, feeling.drive * k,
                1f + (feeling.vol - 1f) * k, false);
        f.level = 0.17f + (feeling.level - 0.17f) * k;
        if (kind == null) return f;
        Style m = new Style("mix", kind.pf * f.pf, kind.tf * f.tf, Math.max(kind.vib, f.vib), Math.max(kind.husk, f.husk), Math.max(kind.drive, f.drive),
                kind.vol * f.vol, kind.dull);
        m.level = kind.level + (f.level - 0.17f);
        m.presence = kind.presence;
        m.warm = kind.warm;
        m.air = kind.air;
        m.space = kind.space;
        m.room = kind.room;
        return m;
    }

    /** The same kind of voice made from a higher voice (a woman's, where the phone has no man's): lowered that much more. */
    static Style lower(Style s, float by) {
        Style m = new Style(s.name, s.pf * by, s.tf, s.vib, s.husk, s.drive, s.vol, s.dull);
        m.level = s.level;
        m.presence = s.presence;
        m.warm = s.warm;
        m.air = s.air;
        m.space = s.space;
        m.room = s.room;
        return m;
    }

    /** Kinds of voice the owner removed ("squeaky", "jesus"…, without "_fil"): such a character is read in the plain voice instead. */
    static volatile java.util.Set<String> removedKinds = new java.util.HashSet<>();

    static boolean removed(String name) {
        return name != null && removedKinds.contains(name.replaceAll("_fil$", ""));
    }

    /** The kind of voice as reading uses it: null when there is none or the owner removed it (then the plain voice reads). */
    static Style use(String name) {
        return removed(name) ? null : get(name);
    }

    /** The kind of voice by name: the user's own settings for it (applied in the voice lab) when there are any, else the built-in ones. */
    static Style get(String name) {
        if (name == null || name.isEmpty()) return null;
        Style mine = CUSTOM.get(name);
        return mine != null ? mine : STYLES.get(name);
    }

    /** The phone voice the Father, the Son and the Holy Spirit have in Tagalog unless the user chose another: Google's FIE. */
    static String defaultVoice(String name) {
        return name != null && name.endsWith("_fil") ? "fil-ph-x-fie" : null;
    }

    static Style builtIn(String name) {
        return name == null || name.isEmpty() ? null : STYLES.get(name);
    }

    // ------------------------------------------------------------ the user's own settings (voice lab > Apply)

    private static final Map<String, Style> CUSTOM = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> CUSTOM_VOICE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean customLoaded;

    static boolean isCustom(String name) {
        return name != null && CUSTOM.containsKey(name);
    }

    /** One kind of voice as the voice lab's "Paste and play" takes it: a line that can be pasted back to get the same sound. */
    static String pasteLine(String name, Style st, String lang, String voice) {
        return String.format(java.util.Locale.US,
                "preset: %s, language: %s, voice: %s, pitch %.2f, tempo %.2f, tremble %.3f, breath %.2f, grit %.2f, clarity %.2f, warmth %.2f, air %.2f, room %.2f, room size %.1f, level %.2f, volume %.2f, muffle %s",
                name, lang, voice == null || voice.isEmpty() ? "usual" : voice.replaceFirst("^[a-z]{2,3}-[a-z]{2}-x-", ""), st.pf, st.tf, st.vib, st.husk, st.drive, st.presence, st.warm, st.air,
                st.space, st.room, st.level, st.vol, st.dull ? "on" : "off");
    }

    /** What the user applied in the voice lab, one line each, for the log's top: so the numbers can be read back from it. */
    static String appliedForLog(android.content.Context c) {
        loadCustom(c);
        if (CUSTOM.isEmpty()) return "voice lab applied: none (built-in settings)";
        StringBuilder sb = new StringBuilder("voice lab applied:");
        java.util.List<String> names = new java.util.ArrayList<>(CUSTOM.keySet());
        java.util.Collections.sort(names);
        for (String n : names) {
            sb.append("\n  ").append(pasteLine(n, CUSTOM.get(n), n.endsWith("_fil") ? "Tagalog" : "English", CUSTOM_VOICE.get(n)));
        }
        return sb.toString();
    }

    /** The phone voice the user chose for this kind of voice (its name without -local/-network), or null. */
    static String customVoice(String name) {
        return name == null ? null : CUSTOM_VOICE.get(name);
    }

    static void loadCustom(android.content.Context c) {
        if (customLoaded) return;
        customLoaded = true;
        try {
            for (Map.Entry<String, ?> e : c.getSharedPreferences("voice_custom", android.content.Context.MODE_PRIVATE).getAll().entrySet()) {
                String k = e.getKey(), v = String.valueOf(e.getValue());
                if (k.startsWith("fx_")) {
                    Style st = decode(k.substring(3), v);
                    if (st != null) CUSTOM.put(k.substring(3), st);
                } else if (k.startsWith("voice_") && !v.isEmpty()) {
                    CUSTOM_VOICE.put(k.substring(6), v);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** These settings are how ZYLENA reads this kind of voice from now on; voice: the phone voice to make it from, or empty. */
    static void setCustom(android.content.Context c, String name, Style st, String voice) {
        loadCustom(c);
        CUSTOM.put(name, st);
        android.content.SharedPreferences.Editor ed = c.getSharedPreferences("voice_custom", android.content.Context.MODE_PRIVATE).edit();
        ed.putString("fx_" + name, encode(st));
        if (voice == null || voice.isEmpty()) {
            CUSTOM_VOICE.remove(name);
            ed.remove("voice_" + name);
        } else {
            CUSTOM_VOICE.put(name, voice);
            ed.putString("voice_" + name, voice);
        }
        ed.apply();
    }

    static void clearCustom(android.content.Context c, String name) {
        loadCustom(c);
        CUSTOM.remove(name);
        CUSTOM_VOICE.remove(name);
        c.getSharedPreferences("voice_custom", android.content.Context.MODE_PRIVATE).edit().remove("fx_" + name).remove("voice_" + name).apply();
    }

    static String encode(Style s) {
        return String.format(java.util.Locale.US, "%.3f,%.3f,%.4f,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f",
                s.pf, s.tf, s.vib, s.husk, s.drive, s.vol, s.dull ? 1 : 0, s.level, s.presence, s.warm, s.air, s.space, s.room);
    }

    static Style decode(String name, String v) {
        try {
            String[] p = v.split(",");
            if (p.length < 13) return null;
            float[] f = new float[13];
            for (int i = 0; i < 13; i++) f[i] = Float.parseFloat(p[i]);
            Style st = new Style(name, f[0], f[1], f[2], f[3], f[4], f[5], f[6] > 0.5f);
            st.level = f[7];
            st.presence = f[8];
            st.warm = f[9];
            st.air = f[10];
            st.space = f[11];
            st.room = f[12];
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    /** The names of the kinds of voice, for the voice lab. */
    static java.util.List<String> names() {
        java.util.List<String> n = new java.util.ArrayList<>(STYLES.keySet());
        java.util.Collections.sort(n);
        return n;
    }

    /** The recording made into the character's voice. mono, 16-bit, at the rate it came in. */
    static short[] process(short[] in, int rate, Style st) {
        return process(in, rate, st, st.level, st.presence);
    }

    /** As above, with the loudness it is brought to (speech is about 0.1 to 0.2) and some extra upper-middle range (0 to 1). */
    static short[] process(short[] in, int rate, Style st, float targetRms, float presenceExtra) {
        return process(in, rate, st, targetRms, presenceExtra, null);
    }

    /**
     * stats, when given (5 long), gets what the log shows of it: the recording's average loudness and its peak, how much it
     * was turned up (or down), the peak before the peaks were held in, and the share of the sound that had to be held in;
     * 7 long, also where the words begin and end in what comes back (in samples).
     */
    static short[] process(short[] in, int rate, Style st, float targetRms, float presenceExtra, float[] stats) {
        int n = in.length;
        if (n < 16) return in;
        float[] x = new float[n];
        double inSum = 0;
        float inPeak = 0;
        for (int i = 0; i < n; i++) {
            x[i] = in[i] / 32768f;
            inSum += x[i] * x[i];
            inPeak = Math.max(inPeak, Math.abs(x[i]));
        }
        if (stats != null && stats.length >= 5) {
            stats[0] = (float) Math.sqrt(inSum / n);
            stats[1] = inPeak;
        }

        // Pitch (and with it tempo): the recording is read faster or slower; a wobble makes an old voice tremble.
        int outLen = (int) (n / st.pf) + 1;
        float[] y = new float[outLen];
        double pos = 0;
        int m = 0;
        for (; m < outLen && pos < n - 1; m++) {
            // (Read between the samples along a curve through four of them: a straight line dulls and roughens the top.)
            int i0 = (int) pos;
            float f = (float) (pos - i0);
            float a = x[i0 > 0 ? i0 - 1 : 0], b = x[i0], c = x[i0 + 1], d = x[i0 + 2 < n ? i0 + 2 : n - 1];
            y[m] = b + 0.5f * f * (c - a + f * (2 * a - 5 * b + 4 * c - d + f * (3 * (b - c) + d - a)));
            double t = m / (double) rate;
            pos += st.pf * (1 + (st.vib > 0 ? st.vib * Math.sin(2 * Math.PI * 5.2 * t) : 0));
        }
        float[] z = new float[m];
        System.arraycopy(y, 0, z, 0, m);
        x = null; // (let go of: a long recording needs a lot of memory for each copy)
        y = null;

        // (The "s" sounds are taken before the heavy voice loses its high end.)
        float[] hissBand = st.hiss > 0 && rate > 14000 ? band(z, 6800f, 3600f, rate) : null;
        // A heavy voice has less of the high end.
        if (st.dull) {
            float a = (float) (1 - Math.exp(-2 * Math.PI * 5200.0 / rate)), s = 0;
            for (int i = 0; i < m; i++) {
                s += a * (z[i] - s);
                z[i] = s;
            }
        }
        // (No added breath or hiss: noise mixed into the voice sounds like a radio losing its signal. A husky or whispered
        // voice is made by pitch, tempo and loudness instead.)
        // Grit: pushed into a soft clip.
        float peak = 1e-4f;
        for (int i = 0; i < m; i++) peak = Math.max(peak, Math.abs(z[i]));
        if (st.drive > 0) {
            float g = 1 + 6 * st.drive;
            float norm = (float) Math.tanh(g);
            for (int i = 0; i < m; i++) z[i] = (float) Math.tanh(g * z[i] / peak) / norm * peak;
        }
        // A lowered voice loses what carries on a small speaker: a little of the upper-middle range is brought back.
        float pres = Math.max(0f, 0.95f - st.pf) * 2.5f + presenceExtra;
        if (pres > 0) {
            float[] pb = band(z, 2200f, 1800f, rate);
            for (int i = 0; i < m; i++) z[i] += pres * pb[i];
        }
        // A snake's hiss: the "s" and "sh" sounds (the only loud sounds up there) made sharper and stronger.
        if (hissBand != null) for (int i = 0; i < m; i++) z[i] += st.hiss * 3f * hissBand[i];
        // Warmth: more of the low end. Air: more of the top.
        if (st.warm > 0) {
            float a = (float) (1 - Math.exp(-2 * Math.PI * 260.0 / rate)), s = 0;
            for (int i = 0; i < m; i++) {
                s += a * (z[i] - s);
                z[i] += st.warm * s;
            }
        }
        if (st.air > 0) {
            float a = (float) (1 - Math.exp(-2 * Math.PI * 4500.0 / rate)), s = 0;
            for (int i = 0; i < m; i++) {
                s += a * (z[i] - s);
                z[i] += st.air * (z[i] - s);
            }
        }
        // Every voice made here is brought to the loudness of speech, with the peaks kept in check: a deep or soft voice stays audible.
        double sum = 0;
        for (int i = 0; i < m; i++) sum += z[i] * z[i];
        float rms = (float) Math.sqrt(sum / Math.max(1, m)) + 1e-6f;
        float gain = Math.min(6f, targetRms / rms);
        for (int i = 0; i < m; i++) z[i] *= gain;
        // Where the words begin and end (before the room rings on), so a long line's pieces follow one another like sentences.
        if (stats != null && stats.length >= 7) {
            final float quiet = 0.008f; // the voice's own silence is far below this; its softest sounds are above it
            int first = -1, last = -1;
            for (int i = 0; i < m && first < 0; i++) if (Math.abs(z[i]) > quiet) first = i;
            for (int i = m - 1; i >= 0 && last < 0; i--) if (Math.abs(z[i]) > quiet) last = i;
            stats[5] = first < 0 ? 0 : first;
            stats[6] = last < 0 ? m : last + 1;
        }
        // The room the voice is heard in (it rings on a little after the last word).
        if (st.space > 0) z = reverb(z, rate, Math.min(0.5f, st.space), Math.max(0.2f, Math.min(3f, st.room)));
        float before = 0;
        for (float v : z) before = Math.max(before, Math.abs(v));
        float held = limit(z, rate, 0.92f);
        if (stats != null && stats.length >= 5) {
            stats[2] = gain;
            stats[3] = before;
            stats[4] = held;
        }
        short[] out = new short[z.length];
        for (int i = 0; i < z.length; i++) out[i] = (short) Math.round(Math.max(-1f, Math.min(1f, z[i])) * 32767);
        return out;
    }

    private static final int[] COMBS = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617}, ALLPASSES = {556, 441, 341, 225};

    /**
     * The voice with a room around it: `amount` is how loud the room is beside the voice (0.2: a fifth as loud), `seconds`
     * how long it rings. The sound comes back longer by the part of the ringing that is still heard after the last word.
     */
    private static float[] reverb(float[] x, int rate, float amount, float seconds) {
        int n = x.length, len = n + (int) (Math.min(0.8f, seconds * 0.45f) * rate), pre = (int) (0.018f * rate);
        double sc = rate / 44100.0;
        // What goes into the room: a moment later, and without the low end (a room full of bass is a muddy one).
        float[] in = new float[len];
        float hp = (float) (1 - Math.exp(-2 * Math.PI * 220.0 / rate)), s = 0;
        for (int i = 0; i < n; i++) {
            s += hp * (x[i] - s);
            if (i + pre < len) in[i + pre] = x[i] - s;
        }
        float[] wet = new float[len];
        for (int d : COMBS) {
            int size = Math.max(8, (int) (d * sc));
            float fb = (float) Math.pow(10, -3.0 * size / (seconds * rate)), damp = 0.28f, keep = 0;
            float[] buf = new float[size];
            for (int i = 0, k = 0; i < len; i++) {
                float y = buf[k];
                keep = y * (1 - damp) + keep * damp;
                buf[k] = in[i] + keep * fb;
                wet[i] += y;
                if (++k == size) k = 0;
            }
        }
        for (int d : ALLPASSES) {
            int size = Math.max(4, (int) (d * sc));
            float[] buf = new float[size];
            for (int i = 0, k = 0; i < len; i++) {
                float b = buf[k], v = wet[i];
                wet[i] = b - v;
                buf[k] = v + b * 0.5f;
                if (++k == size) k = 0;
            }
        }
        double dry = 0, room = 0;
        for (int i = 0; i < n; i++) {
            dry += x[i] * x[i];
            room += wet[i] * wet[i];
        }
        float k = room <= 0 ? 0 : amount * (float) Math.sqrt(dry / room);
        float[] out = in; // (what went into the room is not needed any more: its memory is used again)
        int fade = Math.min(len - n, (int) (0.12f * rate));
        for (int i = 0; i < len; i++) {
            float w = k * wet[i];
            if (i >= len - fade) w *= (len - i) / (float) fade;
            out[i] = (i < n ? x[i] : 0) + w;
        }
        return out;
    }

    /**
     * Keeps the peaks under the ceiling by turning the sound down for a moment around each one, smoothly (the sound is
     * looked at a few thousandths of a second ahead), instead of bending the wave, which roughens a voice. Returns the share
     * of the sound that had to be turned down.
     */
    private static float limit(float[] z, int rate, float ceil) {
        int n = z.length, look = Math.max(2, rate * 4 / 1000), half = look / 2;
        float peak = 0;
        for (float v : z) peak = Math.max(peak, Math.abs(v));
        if (peak <= ceil) return 0f;
        // The gain each sample needs, then the least of them within `look` samples either side.
        float[] need = new float[n], least = new float[n];
        for (int i = 0; i < n; i++) {
            float a = Math.abs(z[i]);
            need[i] = a > ceil ? ceil / a : 1f;
        }
        int[] q = new int[n];
        int head = 0, tail = 0, next = 0;
        for (int i = 0; i < n; i++) {
            for (int hi = Math.min(n - 1, i + look); next <= hi; next++) {
                while (tail > head && need[q[tail - 1]] >= need[next]) tail--;
                q[tail++] = next;
            }
            while (q[head] < i - look) head++;
            least[i] = need[q[head]];
        }
        // It comes back up slowly (no pumping), and goes down over the look-ahead (no click).
        float rel = (float) (1 - Math.exp(-1.0 / (0.08 * rate))), g = 1f;
        for (int i = 0; i < n; i++) {
            g = Math.min(least[i], g + (1f - g) * rel);
            least[i] = g;
        }
        double run = 0;
        int lo = 0, hi = 0; // the window [lo, hi) summed in `run`
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - half), b = Math.min(n, i + half + 1);
            while (hi < b) run += least[hi++];
            while (lo < a) run -= least[lo++];
            need[i] = (float) (run / (b - a));
        }
        int heldIn = 0;
        for (int i = 0; i < n; i++) {
            z[i] *= need[i];
            if (need[i] < 0.98f) heldIn++;
        }
        return heldIn / (float) n; // the share of the sound that was turned down by more than 2%
    }

    /** A resonance at hz with the given width, applied to the whole signal. */
    private static float[] band(float[] x, float hz, float width, int rate) {
        double w0 = 2 * Math.PI * Math.min(hz, rate * 0.45) / rate, q = Math.max(0.5, hz / Math.max(40f, width));
        double al = Math.sin(w0) / (2 * q), a0 = 1 + al, a1 = -2 * Math.cos(w0) / a0, a2 = (1 - al) / a0, b0 = al / a0;
        float[] y = new float[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = b0 * x[i] - b0 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x[i];
            y2 = y1;
            y1 = v;
            y[i] = (float) v;
        }
        return y;
    }
}
