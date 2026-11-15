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

    static {
        // The Father: majestic and calm (a voice "as the sound of many waters"). Milder in Tagalog, where there is only one man's voice.
        add("deep", 0.84f, 0.84f, 0f, 0f, 0f, 1f, false);
        add("deep_fil", 0.90f, 0.92f, 0f, 0f, 0f, 1f, false);
        // The Son: warm, clear, musical and gentle, with authority behind it ("gracious words", "a sweet melody in His voice").
        add("jesus", 0.95f, 0.95f, 0f, 0.03f, 0f, 1f, false);
        add("jesus_fil", 0.97f, 0.97f, 0f, 0.03f, 0f, 1f, false);
        // The Holy Spirit: soft, "a still small voice" (kept audible).
        add("spirit", 1.0f, 0.90f, 0f, 0.07f, 0f, 0.95f, false);
        add("spirit_fil", 1.0f, 0.93f, 0f, 0.07f, 0f, 0.95f, false);
        add("child", 1.30f, 1.05f, 0f, 0f, 0f, 1f, false);
        add("squeaky", 1.38f, 1.08f, 0f, 0f, 0f, 1f, false);
        add("cute", 1.18f, 1.04f, 0f, 0f, 0f, 1f, false);
        add("teen", 1.07f, 1.02f, 0f, 0f, 0f, 1f, false);
        add("elder", 0.90f, 0.88f, 0.018f, 0.08f, 0f, 0.95f, false);
        add("husky", 0.93f, 0.95f, 0f, 0.22f, 0.08f, 1f, false);
        add("boss", 0.80f, 0.86f, 0f, 0.16f, 0.30f, 1f, true); // a mafia boss: low, slow, gravelly
        add("gruff", 0.86f, 0.93f, 0f, 0.22f, 0.25f, 1f, true);
        add("bass", 0.84f, 0.92f, 0f, 0.05f, 0.05f, 1f, true);
        add("villain", 0.88f, 0.90f, 0f, 0.06f, 0.12f, 1f, true);
        add("maid", 1.08f, 0.98f, 0f, 0.03f, 0f, 0.92f, false); // polite, light
        add("butler", 0.94f, 0.94f, 0f, 0.02f, 0f, 0.95f, false);
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
        feel("r", 1.07f, 1.22f, 0f, 0f, 0f, 1.0f, 0.18f); // rambling: fast and rushed
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
        return m;
    }

    static Style get(String name) {
        return name == null || name.isEmpty() ? null : STYLES.get(name);
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
        int n = in.length;
        if (n < 16) return in;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = in[i] / 32768f;

        // Pitch (and with it tempo): the recording is read faster or slower; a wobble makes an old voice tremble.
        int outLen = (int) (n / st.pf) + 1;
        float[] y = new float[outLen];
        double pos = 0;
        int m = 0;
        for (; m < outLen && pos < n - 1; m++) {
            int i0 = (int) pos;
            float f = (float) (pos - i0);
            y[m] = x[i0] * (1 - f) + x[i0 + 1] * f;
            double t = m / (double) rate;
            pos += st.pf * (1 + (st.vib > 0 ? st.vib * Math.sin(2 * Math.PI * 5.2 * t) : 0));
        }
        float[] z = new float[m];
        System.arraycopy(y, 0, z, 0, m);

        // A heavy voice has less of the high end.
        if (st.dull) {
            float a = (float) (1 - Math.exp(-2 * Math.PI * 5200.0 / rate)), s = 0;
            for (int i = 0; i < m; i++) {
                s += a * (z[i] - s);
                z[i] = s;
            }
        }
        // Breath: soft noise from the middle-upper range, as loud as the voice is at that moment, so it is there only while
        // speaking. Kept gentle: hiss pushed any louder (and then brought up with the rest) sounds like a broken speaker.
        if (st.husk > 0) {
            Random r = new Random(5);
            float[] nzs = new float[m];
            for (int i = 0; i < m; i++) nzs[i] = r.nextFloat() * 2 - 1;
            float[] air = band(nzs, 2600f, 2400f, rate);
            float env = 0;
            float up = (float) (1 - Math.exp(-1.0 / (0.006 * rate))), down = (float) (1 - Math.exp(-1.0 / (0.08 * rate)));
            float amount = Math.min(st.husk, 0.3f) * 1.1f;
            for (int i = 0; i < m; i++) {
                float a = Math.abs(z[i]);
                env += (a > env ? up : down) * (a - env);
                z[i] += amount * Math.min(env, 0.3f) * air[i] * 3f;
            }
        }
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
        // Every voice made here is brought to the loudness of speech, with the peaks kept in check: a deep or soft voice stays audible.
        double sum = 0;
        for (int i = 0; i < m; i++) sum += z[i] * z[i];
        float rms = (float) Math.sqrt(sum / Math.max(1, m)) + 1e-6f;
        float gain = Math.min(6f, targetRms / rms);
        short[] out = new short[m];
        for (int i = 0; i < m; i++) out[i] = (short) (0.95f * (float) Math.tanh(z[i] * gain) * 32767);
        return out;
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
