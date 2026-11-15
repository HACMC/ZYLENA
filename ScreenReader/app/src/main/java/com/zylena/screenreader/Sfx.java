package com.zylena.screenreader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Sound effects for stories: a knock, a door, a ringing phone, thunder. They are made here, from tones and
 * noise (no sound files, nothing to download), and played when the sentence that tells of them is read.
 */
final class Sfx {
    private Sfx() {
    }

    static volatile boolean on = true;
    static volatile float volume = 0.7f;

    private static final int RATE = 22050;

    // ------------------------------------------------------------------ what a sentence tells of

    private static final Map<String, Pattern> CUES = new LinkedHashMap<>();

    private static void cue(String name, String regex) {
        CUES.put(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    static {
        // Most specific first: the first that matches is the one played.
        cue("doorbell", "\\bdoor ?bell\\b|\\bbell (?:rang|chimed|sounded) at the door|\\btumunog ang doorbell");
        cue("phone", "\\b(?:phone|cell ?phone|cell|mobile|telephone|smartphone|telepono)\\b[^.!?]{0,25}\\b(?:rang|ringing|rings|ring|buzzed|buzzing|vibrated|tumunog|nag-?ring)\\b"
                + "|\\b(?:ringing|ringtone|ringer)\\b[^.!?]{0,20}\\b(?:phone|cell|mobile)\\b|\\bring ?tone\\b|\\bnag-?ring ang\\b|\\btumunog ang (?:phone|cellphone|telepono)");
        cue("alarm", "\\balarm(?: clock)?\\b[^.!?]{0,20}\\b(?:went off|rang|blared|buzzed|beeped|sounded)\\b|\\balarm clock\\b");
        cue("message", "\\b(?:text|message|notification|email)\\b[^.!?]{0,20}\\b(?:chimed|pinged|dinged|came in|popped up|beeped)\\b|\\bpinged\\b");
        cue("knock", "\\b(?:knock(?:s|ed|ing)?|rapped|rapping|kumatok|katok|tok[ -]tok)\\b(?! (?:out|down|over|off|back|up)\\b)(?!out)");
        cue("slam", "\\bslam(?:med|ming|s)?\\b[^.!?]{0,20}\\b(?:door|shut)\\b|\\bdoor\\b[^.!?]{0,15}\\b(?:slam(?:med|s)?|banged)\\b|\\bisinara nang malakas\\b|\\bbumagsak ang pinto");
        cue("door_open", "\\bdoor\\b[^.!?]{0,25}\\b(?:opened|opens|swung|creaked|creaking|swung open)\\b|\\b(?:opened|opens|opening|pushed open|pushed|pulled open) the door\\b|\\bcreaked open\\b|\\bbumukas ang pinto|\\bbinuksan (?:niya )?ang pinto");
        cue("door_close", "\\b(?:closed|shut|closes|shuts|closing|clicked shut|locked|unlocked) (?:the |her |his |their )?(?:door|gate)\\b|\\bdoor\\b[^.!?]{0,15}\\b(?:closed|shut|clicked|locked)\\b|\\bsinara (?:niya )?ang pinto");
        cue("thunder", "\\bthunder(?:ed|ing|s|clap)?\\b|\\bthunderclap\\b|\\blightning\\b|\\bkumulog\\b|\\bkulog\\b|\\bkidlat\\b|\\bkumidlat\\b");
        cue("gunshot", "\\bgun ?shots?\\b|\\bgunfire\\b|\\bpulled the trigger\\b|\\bfired (?:a|his|her|the) (?:gun|pistol|rifle|shot)\\b|\\bshots? (?:rang|rings|echoed)\\b|\\bputok\\b");
        cue("explosion", "\\bexplo(?:sion|ded|des|de)\\b|\\bdetonat\\w+|\\bbooming\\b|\\bboomed\\b|\\bsumabog\\b|\\bsabog\\b");
        cue("glass", "\\b(?:glass|window|mirror|vase|plate|bottle|cup|mug)\\b[^.!?]{0,20}\\b(?:shattered|shatter|smashed|broke|crashed|cracked)\\b|\\bshattered\\b|\\bshattering\\b|\\bnabasag\\b");
        cue("bell", "\\b(?:church )?bells? (?:rang|tolled|chimed|ringing)\\b|\\bclock (?:struck|chimed|tolled)\\b|\\btolled\\b|\\bkampana\\b");
        cue("heartbeat", "\\bheart(?:beat|beats)?\\b[^.!?]{0,15}\\b(?:pounded|pounding|raced|racing|thudded|hammered|skipped|thumped|thumping|drummed)\\b|\\bheartbeat\\b|\\bheart skipped\\b");
        cue("horn", "\\bhorns? (?:blared|honked|sounded)\\b|\\bhonk(?:ed|ing|s)?\\b|\\bbusina\\b");
        cue("splash", "\\bsplash(?:ed|es|ing)?\\b|\\bdove into the (?:water|pool|lake)\\b|\\bjumped into the (?:water|pool|lake)\\b|\\bsumabog ang tubig\\b");
        cue("applause", "\\bapplau(?:se|ded)\\b|\\bclapped\\b|\\bclapping\\b|\\bcheered\\b|\\bpalakpak\\b");
        cue("footsteps", "\\bfootsteps?\\b|\\bfootfalls?\\b|\\btiptoed?\\b|\\bheels (?:clicked|clacked|echoed)\\b|\\b(?:boots|shoes) (?:thudded|echoed|clicked|scuffed)\\b|\\bpaa?ng yabag\\b|\\byabag\\b");
        cue("rain", "\\brain (?:pattered|poured|fell|drummed|pounded|began to fall)\\b|\\bpouring rain\\b|\\bdownpour\\b|\\braindrops\\b|\\bumuulan\\b|\\bbumuhos ang ulan\\b");
        cue("wind", "\\bwind (?:howled|whistled|roared|blew|rushed|whipped|moaned)\\b|\\bgust of wind\\b|\\bhuni ng hangin\\b|\\bhumangin nang malakas\\b");
        cue("clock", "\\btick(?:ing)?\\b[^.!?]{0,10}\\bclock\\b|\\bclock\\b[^.!?]{0,10}\\btick(?:ed|ing)\\b|\\bticking\\b");
    }

    /** The effect a sentence of a story tells of ("knock", "thunder", …), or "" for none. */
    static String detect(String sentence) {
        if (sentence.length() < 6) return "";
        for (Map.Entry<String, Pattern> e : CUES.entrySet()) if (e.getValue().matcher(sentence).find()) return e.getKey();
        return "";
    }

    private static final Map<String, String> SOUND_WORDS = new HashMap<>();

    static {
        for (String w : new String[]{"ring", "riiing", "brring", "brrring", "bring", "riing", "kring"}) SOUND_WORDS.put(w, "phone");
        for (String w : new String[]{"knock", "tok", "tap", "rap"}) SOUND_WORDS.put(w, "knock");
        for (String w : new String[]{"bang", "slam", "wham"}) SOUND_WORDS.put(w, "slam");
        for (String w : new String[]{"boom", "kaboom", "blast"}) SOUND_WORDS.put(w, "explosion");
        for (String w : new String[]{"ding", "dong", "ding-dong", "dingdong"}) SOUND_WORDS.put(w, "doorbell");
        for (String w : new String[]{"beep", "bleep", "ping"}) SOUND_WORDS.put(w, "message");
        for (String w : new String[]{"honk", "beep-beep"}) SOUND_WORDS.put(w, "horn");
        for (String w : new String[]{"splash", "plop", "plunk"}) SOUND_WORDS.put(w, "splash");
        for (String w : new String[]{"thud", "thump", "stomp"}) SOUND_WORDS.put(w, "footsteps");
        for (String w : new String[]{"crash", "smash", "clatter"}) SOUND_WORDS.put(w, "glass");
        for (String w : new String[]{"click", "clack"}) SOUND_WORDS.put(w, "door_close");
        for (String w : new String[]{"buzz", "bzzt", "bzz", "vroom"}) SOUND_WORDS.put(w, "phone");
        for (String w : new String[]{"creak", "squeak"}) SOUND_WORDS.put(w, "door_open");
        for (String w : new String[]{"clap"}) SOUND_WORDS.put(w, "applause");
        for (String w : new String[]{"tick", "tock", "tick-tock", "ticktock"}) SOUND_WORDS.put(w, "clock");
        for (String w : new String[]{"rumble"}) SOUND_WORDS.put(w, "thunder");
        for (String w : new String[]{"bam", "pow", "bang!"}) SOUND_WORDS.put(w, "gunshot");
    }

    private static final Pattern SOLO = Pattern.compile("^[\\s*_\"“”'‘’(\\[\\-–—]*[A-Za-z][A-Za-z\\-]{1,14}(?:[\\s,.!?*_\\-–—\"”'’)\\]]+[A-Za-z][A-Za-z\\-]{1,14})*[\\s,.!?*_\\-–—\"”'’)\\]]*$");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z\\-]*");

    /**
     * A sentence that is only a sound written out ("Ring, ring!", "*Knock knock*", "BANG!"): the effect that
     * stands for it, to be played instead of saying the word; null when it is anything else.
     */
    static String solo(String sentence) {
        String s = sentence.trim();
        if (s.length() > 40 || !SOLO.matcher(s).matches()) return null;
        java.util.regex.Matcher m = WORD.matcher(s);
        String fx = null;
        int words = 0;
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT).replaceAll("^-+|-+$", "");
            String e = SOUND_WORDS.get(w);
            if (e == null) e = SOUND_WORDS.get(w.replaceAll("(.)\\1{2,}", "$1$1")); // "riiiing", "boooom"
            if (e == null) e = SOUND_WORDS.get(w.replaceAll("(.)\\1+", "$1")); // "knocck"
            if (e == null) return null;
            if (fx == null) fx = e;
            if (++words > 4) return null;
        }
        return fx;
    }

    // ------------------------------------------------------------------ playing

    private static final Map<String, short[]> made = new HashMap<>();
    private static final Map<String, Long> lastAt = new HashMap<>();
    private static long lastAny;
    private static Handler main;

    /** Plays the effect (on a thread of its own; made the first time it is needed). Not twice in a row too soon. */
    static void play(Context c, String name) {
        if (!on || name == null || name.isEmpty() || Prefs.testMode(c)) return;
        long now = System.currentTimeMillis();
        synchronized (lastAt) {
            Long was = lastAt.get(name);
            if (was != null && now - was < 6000) return; // the same sound again and again is tiresome
            if (now - lastAny < 900) return;
            lastAt.put(name, now);
            lastAny = now;
        }
        final float vol = Math.max(0f, Math.min(1f, volume));
        if (vol <= 0.01f) return;
        Thread t = new Thread(() -> {
            try {
                short[] pcm;
                synchronized (made) {
                    pcm = made.get(name);
                    if (pcm == null) {
                        pcm = make(name);
                        if (pcm != null) made.put(name, pcm);
                    }
                }
                if (pcm == null) return;
                AudioTrack tr = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.length * 2).build();
                tr.write(pcm, 0, pcm.length);
                tr.setVolume(vol);
                tr.play();
                Logs.d(c, "sound effect: " + name);
                long ms = pcm.length * 1000L / RATE + 300;
                if (main == null) main = new Handler(Looper.getMainLooper());
                main.postDelayed(() -> {
                    try {
                        tr.release();
                    } catch (Exception ignored) {
                    }
                }, ms);
            } catch (Throwable e) {
                Logs.d(c, "sound effect failed: " + e);
            }
        }, "zylena-sfx");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ making the sounds

    private static short[] make(String name) {
        float[] b;
        switch (name) {
            case "knock":
                b = buf(0.9f);
                for (int i = 0; i < 3; i++) knock(b, 0.02f + i * 0.24f, 1f - i * 0.1f);
                break;
            case "door_open":
                b = buf(1.1f);
                click(b, 0.0f, 0.5f);
                creak(b, 0.08f, 0.95f, 150f, 260f);
                break;
            case "door_close":
                b = buf(0.6f);
                thump(b, 0.0f, 110f, 0.14f, 0.9f);
                noise(b, 0.0f, 0.08f, 0.5f, 900f, 0f);
                click(b, 0.15f, 0.5f);
                break;
            case "slam":
                b = buf(0.9f);
                thump(b, 0.0f, 80f, 0.4f, 1f);
                noise(b, 0.0f, 0.3f, 0.9f, 2500f, 0f);
                break;
            case "phone":
                b = buf(2.6f);
                for (int r = 0; r < 2; r++) ring(b, 0.05f + r * 1.25f, 0.8f);
                break;
            case "message":
                b = buf(0.5f);
                tone(b, 0.0f, 1320f, 0.14f, 0.5f);
                tone(b, 0.13f, 1760f, 0.25f, 0.5f);
                break;
            case "alarm":
                b = buf(1.6f);
                for (int i = 0; i < 4; i++) {
                    tone(b, i * 0.4f, 1000f, 0.12f, 0.45f);
                    tone(b, i * 0.4f + 0.16f, 1000f, 0.12f, 0.45f);
                }
                break;
            case "doorbell":
                b = buf(1.5f);
                bell(b, 0.0f, 880f, 0.7f, 0.7f);
                bell(b, 0.55f, 659f, 0.9f, 0.7f);
                break;
            case "bell":
                b = buf(2.4f);
                bell(b, 0.0f, 392f, 2.2f, 0.8f);
                break;
            case "footsteps":
                b = buf(1.7f);
                for (int i = 0; i < 4; i++) step(b, 0.05f + i * 0.4f, i % 2 == 0 ? 1f : 0.85f);
                break;
            case "thunder":
                b = buf(3f);
                noise(b, 0.0f, 2.8f, 0.9f, 220f, 0.25f);
                thump(b, 0.1f, 55f, 1.2f, 0.8f);
                break;
            case "gunshot":
                b = buf(0.9f);
                noise(b, 0.0f, 0.5f, 1f, 6000f, 0.02f);
                thump(b, 0.0f, 120f, 0.25f, 1f);
                break;
            case "explosion":
                b = buf(2.2f);
                noise(b, 0.0f, 2f, 1f, 900f, 0.05f);
                thump(b, 0.0f, 70f, 1.1f, 1f);
                break;
            case "glass":
                b = buf(1.1f);
                noise(b, 0.0f, 0.12f, 0.8f, 9000f, 0.01f);
                Random r = new Random(7);
                for (int i = 0; i < 9; i++) tone(b, 0.03f + r.nextFloat() * 0.6f, 2800f + r.nextInt(3500), 0.18f, 0.18f);
                break;
            case "heartbeat":
                b = buf(1.9f);
                for (int i = 0; i < 2; i++) {
                    thump(b, i * 0.85f, 62f, 0.16f, 1f);
                    thump(b, i * 0.85f + 0.22f, 55f, 0.18f, 0.75f);
                }
                break;
            case "horn":
                b = buf(1.1f);
                horn(b, 0.0f, 0.35f);
                horn(b, 0.5f, 0.45f);
                break;
            case "splash":
                b = buf(0.9f);
                noise(b, 0.0f, 0.7f, 0.8f, 3500f, 0.02f);
                break;
            case "applause":
                b = buf(2.2f);
                Random a = new Random(3);
                for (int i = 0; i < 60; i++) {
                    float at = a.nextFloat() * 1.7f;
                    float g = 0.35f * (float) Math.sin(Math.PI * Math.min(1f, at / 1.7f));
                    noise(b, at, 0.03f, 0.2f + g, 4500f, 0f);
                }
                break;
            case "rain":
                b = buf(2.8f);
                noise(b, 0.0f, 2.8f, 0.35f, 7000f, 0.7f);
                break;
            case "wind":
                b = buf(3f);
                noise(b, 0.0f, 3f, 0.6f, 600f, 0.8f);
                break;
            case "clock":
                b = buf(2f);
                for (int i = 0; i < 4; i++) {
                    noise(b, i * 0.5f, 0.015f, 0.7f, 5000f, 0f);
                    tone(b, i * 0.5f, i % 2 == 0 ? 1800f : 1400f, 0.03f, 0.3f);
                }
                break;
            default:
                return null;
        }
        short[] out = new short[b.length];
        float peak = 0.0001f;
        for (float v : b) peak = Math.max(peak, Math.abs(v));
        float g = peak > 0.9f ? 0.9f / peak : 1f;
        for (int i = 0; i < b.length; i++) out[i] = (short) (Math.max(-1f, Math.min(1f, b[i] * g)) * 30000);
        return out;
    }

    private static float[] buf(float seconds) {
        return new float[(int) (seconds * RATE)];
    }

    /** A sine tone with a quick attack and a smooth fade. */
    private static void tone(float[] b, float at, float hz, float len, float amp) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) n;
            float env = Math.min(1f, i / 80f) * (1f - t) * (1f - t);
            b[s + i] += amp * env * (float) Math.sin(2 * Math.PI * hz * i / RATE);
        }
    }

    /** A low thump: a sine that drops in pitch while it fades. */
    private static void thump(float[] b, float at, float hz, float len, float amp) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        double ph = 0;
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) n;
            double f = hz * (1.0 - 0.5 * t);
            ph += 2 * Math.PI * f / RATE;
            float env = Math.min(1f, i / 40f) * (float) Math.exp(-5.0 * t);
            b[s + i] += amp * env * (float) Math.sin(ph);
        }
    }

    /**
     * Filtered noise. cutoff: where the sound's top lies (Hz); swell: 0 = a plain fade out, up to 1 = it
     * rises and falls slowly (wind, thunder).
     */
    private static void noise(float[] b, float at, float len, float amp, float cutoff, float swell) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        Random r = new Random(Math.round(at * 1000) + n);
        float a = (float) (1 - Math.exp(-2 * Math.PI * cutoff / RATE)), y = 0;
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) n;
            y += a * ((r.nextFloat() * 2 - 1) - y);
            float fade = Math.min(1f, i / 60f) * (float) Math.exp(-3.0 * t);
            float env = swell <= 0f ? fade : (1 - swell) * fade + swell * (float) Math.sin(Math.PI * t) * (0.7f + 0.3f * (float) Math.sin(2 * Math.PI * 3 * t));
            b[s + i] += amp * env * y * (cutoff < 1000f ? 3f : 1.5f);
        }
    }

    private static void click(float[] b, float at, float amp) {
        noise(b, at, 0.02f, amp, 3500f, 0f);
    }

    private static void knock(float[] b, float at, float amp) {
        thump(b, at, 170f, 0.09f, amp);
        noise(b, at, 0.03f, 0.5f * amp, 1500f, 0f);
    }

    private static void step(float[] b, float at, float amp) {
        thump(b, at, 95f, 0.11f, 0.8f * amp);
        noise(b, at, 0.06f, 0.5f * amp, 700f, 0f);
    }

    /** A door's creak: a buzzing tone that wavers upward, through a changing filter. */
    private static void creak(float[] b, float at, float len, float from, float to) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        Random r = new Random(11);
        double ph = 0;
        float y = 0;
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) n;
            double f = from + (to - from) * t + 30 * Math.sin(2 * Math.PI * 9 * t) + r.nextFloat() * 14;
            ph += f / RATE;
            float saw = (float) (ph - Math.floor(ph)) * 2 - 1;
            y += 0.18f * (saw - y);
            float env = (float) Math.sin(Math.PI * t) * 0.8f;
            b[s + i] += 0.55f * env * y;
        }
    }

    /** A telephone ring: two tones together, switched on and off fast, for about a second. */
    private static void ring(float[] b, float at, float len) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) RATE;
            float gate = ((int) (t * 40)) % 2 == 0 ? 1f : 0.15f;
            float env = Math.min(1f, i / 200f) * Math.min(1f, (n - i) / 400f);
            float v = (float) (Math.sin(2 * Math.PI * 440 * t) + Math.sin(2 * Math.PI * 480 * t)) * 0.5f;
            b[s + i] += 0.5f * env * gate * v;
        }
    }

    /** A struck bell: a few overtones that fade away at different speeds. */
    private static void bell(float[] b, float at, float hz, float len, float amp) {
        float[] parts = {1f, 2.01f, 2.76f, 4.07f, 5.4f};
        float[] gain = {1f, 0.5f, 0.45f, 0.25f, 0.15f};
        int s = (int) (at * RATE), n = (int) (len * RATE);
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) RATE, v = 0;
            for (int k = 0; k < parts.length; k++) {
                v += gain[k] * (float) (Math.sin(2 * Math.PI * hz * parts[k] * t) * Math.exp(-t * (2.2 + 2.2 * k) / len * 1.6));
            }
            b[s + i] += amp * 0.45f * Math.min(1f, i / 30f) * v;
        }
    }

    private static void horn(float[] b, float at, float len) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) RATE;
            float env = Math.min(1f, i / 300f) * Math.min(1f, (n - i) / 600f);
            float v = (float) (Math.signum(Math.sin(2 * Math.PI * 400 * t)) * 0.5 + Math.signum(Math.sin(2 * Math.PI * 500 * t)) * 0.5);
            b[s + i] += 0.3f * env * v;
        }
    }
}
