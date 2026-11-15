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
        add("deep", 0.84f, 0.84f, 0f, 0f, 0f, 1f, false); // God, Jesus, the Holy Spirit: weight and calm
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

    static Style get(String name) {
        return name == null || name.isEmpty() ? null : STYLES.get(name);
    }

    /** The recording made into the character's voice. mono, 16-bit, at the rate it came in. */
    static short[] process(short[] in, int rate, Style st) {
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
            float a = (float) (1 - Math.exp(-2 * Math.PI * 3800.0 / rate)), s = 0;
            for (int i = 0; i < m; i++) {
                s += a * (z[i] - s);
                z[i] = s;
            }
        }
        // Breath: noise from the upper range, as loud as the voice is at that moment, so it is there only while speaking.
        if (st.husk > 0) {
            Random r = new Random(5);
            float env = 0, lp = 0;
            float up = (float) (1 - Math.exp(-1.0 / (0.004 * rate))), down = (float) (1 - Math.exp(-1.0 / (0.05 * rate)));
            float hpA = (float) (1 - Math.exp(-2 * Math.PI * 1800.0 / rate));
            for (int i = 0; i < m; i++) {
                float a = Math.abs(z[i]);
                env += (a > env ? up : down) * (a - env);
                float nz = r.nextFloat() * 2 - 1;
                lp += hpA * (nz - lp);
                z[i] += st.husk * 2.2f * env * (nz - lp);
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
        peak = 1e-4f;
        for (int i = 0; i < m; i++) peak = Math.max(peak, Math.abs(z[i]));
        float gain = 0.9f / peak;
        short[] out = new short[m];
        for (int i = 0; i < m; i++) out[i] = (short) (Math.max(-1f, Math.min(1f, z[i] * gain)) * 32767);
        return out;
    }
}
