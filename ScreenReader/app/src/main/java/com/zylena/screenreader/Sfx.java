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
        cue("laugh", "\\b(?:laugh(?:ed|s|ing|ter)?|chuckl\\w+|cackl\\w+|guffaw\\w*|howled with laughter|burst out laughing|tumawa|humalakhak|tawanan)\\b");
        cue("giggle", "\\b(?:giggl\\w+|snicker\\w*|tittered|humagikgik|hagikgik)\\b");
        cue("scream", "\\b(?:scream(?:ed|s|ing)?|shriek\\w*|screech\\w*|tumili|tili|sumigaw nang malakas)\\b");
        cue("gasp", "\\b(?:gasp(?:ed|s|ing)?|napasinghap|singhap)\\b");
        cue("cough", "\\b(?:cough(?:ed|s|ing)?|cleared (?:his|her|their) throat|umubo|ubo)\\b|\\bahem\\b");
        cue("sneeze", "\\b(?:sneez\\w+|bumahing|hatsing)\\b");
        cue("sigh", "\\b(?:sigh(?:ed|s|ing)?|buntong-?hininga|huminga nang malalim)\\b");
        cue("whistle", "\\b(?:whistl(?:ed|es|ing)|sumipol|pito)\\b");
        cue("kiss", "\\b(?:kiss(?:ed|es|ing)?|hinalikan|humalik|halik)\\b");
        cue("sword", "\\bswords?\\b[^.!?]{0,20}\\b(?:clashed|clanged|drew|swung|rang|struck)\\b|\\bunsheathed\\b|\\bclash of (?:swords|steel)\\b");
        cue("engine", "\\b(?:engine|motor|motorcycle|car|truck)\\b[^.!?]{0,20}\\b(?:roared|revved|rumbled|purred|sputtered|started up|came to life)\\b|\\brevved\\b");
        cue("siren", "\\bsirens?\\b|\\bsumirena\\b");
        cue("drip", "\\bdrip(?:ped|ping|s)?\\b");
        cue("fire", "\\b(?:fire|flames?|logs?|fireplace)\\b[^.!?]{0,20}\\b(?:crackled|crackling|roared|popped)\\b|\\bcrackling fire\\b");
        cue("bird", "\\bbirds? (?:chirped|sang|twittered|chirping|singing|tweeted|called)\\b|\\bchirp(?:ed|ing|s)?\\b|\\bhuni ng ibon\\b");
        cue("dog", "\\b(?:dog|puppy|hound)\\b[^.!?]{0,15}\\b(?:barked|barking|growled|howled|yapped)\\b|\\bbarked\\b|\\bumatungal\\b|\\btumahol\\b");
        cue("cat", "\\b(?:cat|kitten)\\b[^.!?]{0,15}\\b(?:meowed|mewed|purred|hissed)\\b|\\bmeow\\w*|\\bngiyaw\\b");
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
        for (String w : new String[]{"buzz", "bzzt", "bzz"}) SOUND_WORDS.put(w, "phone");
        for (String w : new String[]{"creak", "squeak"}) SOUND_WORDS.put(w, "door_open");
        for (String w : new String[]{"clap"}) SOUND_WORDS.put(w, "applause");
        for (String w : new String[]{"tick", "tock", "tick-tock", "ticktock"}) SOUND_WORDS.put(w, "clock");
        for (String w : new String[]{"rumble"}) SOUND_WORDS.put(w, "thunder");
        for (String w : new String[]{"bam", "pow", "bang!"}) SOUND_WORDS.put(w, "gunshot");
        for (String w : new String[]{"ha", "hah", "heh", "haha", "hahaha", "hahahaha", "bwahaha", "muahaha", "lol"}) SOUND_WORDS.put(w, "laugh");
        for (String w : new String[]{"hehe", "hihi", "tehee", "teehee", "hehehe", "hihihi"}) SOUND_WORDS.put(w, "giggle");
        for (String w : new String[]{"aah", "aa", "ahhh", "eek"}) SOUND_WORDS.put(w, "scream");
        for (String w : new String[]{"gasp", "hah-"}) SOUND_WORDS.put(w, "gasp");
        for (String w : new String[]{"cough", "ahem", "ahm"}) SOUND_WORDS.put(w, "cough");
        for (String w : new String[]{"achoo", "ahchoo", "atchoo", "hatsing"}) SOUND_WORDS.put(w, "sneeze");
        for (String w : new String[]{"sigh", "hay", "haay", "haaay"}) SOUND_WORDS.put(w, "sigh");
        for (String w : new String[]{"muah", "mwah", "smooch", "mwuah"}) SOUND_WORDS.put(w, "kiss");
        for (String w : new String[]{"meow", "miao", "ngiyaw", "mew"}) SOUND_WORDS.put(w, "cat");
        for (String w : new String[]{"woof", "arf", "bark", "bow-wow", "aw-aw", "ruff"}) SOUND_WORDS.put(w, "dog");
        for (String w : new String[]{"tweet", "chirp", "tweet-tweet", "chirp-chirp"}) SOUND_WORDS.put(w, "bird");
        for (String w : new String[]{"drip", "plip"}) SOUND_WORDS.put(w, "drip");
        for (String w : new String[]{"whistle", "tweeet"}) SOUND_WORDS.put(w, "whistle");
        for (String w : new String[]{"vroom", "brum", "brrm"}) SOUND_WORDS.put(w, "engine");
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

    private static final java.util.Set<String> PERSON = new java.util.HashSet<>(java.util.Arrays.asList(
            "laugh", "giggle", "scream", "gasp", "cough", "sneeze", "sigh", "whistle", "kiss", "dog", "cat", "bird"));
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
            // The same sound again and again is tiresome; the sounds of a person ("laughed", "sighed") come up the most.
            if (was != null && now - was < (PERSON.contains(name) ? 14000 : 6000)) return;
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
            case "laugh":
                b = buf(1.4f);
                for (int i = 0; i < 5; i++) voice(b, 0.02f + i * 0.21f, 0.15f, 250f - i * 12f, 220f - i * 14f, 800f, 1250f, 0.9f - i * 0.1f, 0.35f, 0f);
                break;
            case "giggle":
                b = buf(1.1f);
                for (int i = 0; i < 6; i++) voice(b, 0.02f + i * 0.14f + (i % 2) * 0.02f, 0.09f, 430f + (i % 3) * 25f, 400f, 330f, 2400f, 0.8f, 0.2f, 0f);
                break;
            case "scream":
                b = buf(1.2f);
                voice(b, 0.0f, 1.1f, 620f, 980f, 850f, 1400f, 1f, 0.25f, 7f);
                break;
            case "gasp":
                b = buf(0.6f);
                bandNoise(b, 0.0f, 0.45f, 900f, 2800f, 0.9f, 0.4f);
                break;
            case "cough":
                b = buf(0.9f);
                for (int i = 0; i < 2; i++) {
                    voice(b, 0.03f + i * 0.28f, 0.1f, 140f, 110f, 550f, 1500f, 0.8f, 0.9f, 0f);
                    bandNoise(b, 0.03f + i * 0.28f, 0.1f, 600f, 3000f, 0.5f, 0.1f);
                }
                break;
            case "sneeze":
                b = buf(1.1f);
                bandNoise(b, 0.0f, 0.55f, 800f, 2500f, 0.5f, 0.85f);
                bandNoise(b, 0.55f, 0.18f, 1500f, 7000f, 1f, 0.05f);
                voice(b, 0.56f, 0.18f, 300f, 200f, 650f, 1300f, 0.6f, 0.8f, 0f);
                break;
            case "sigh":
                b = buf(1.3f);
                bandNoise(b, 0.0f, 1.1f, 500f, 2200f, 0.7f, 0.5f);
                voice(b, 0.05f, 1.0f, 190f, 150f, 600f, 1100f, 0.25f, 0.9f, 0f);
                break;
            case "whistle":
                b = buf(1.0f);
                sweep(b, 0.0f, 0.35f, 1500f, 2600f, 0.55f);
                sweep(b, 0.4f, 0.4f, 2600f, 1400f, 0.55f);
                break;
            case "kiss":
                b = buf(0.4f);
                sweep(b, 0.0f, 0.07f, 700f, 1500f, 0.35f);
                bandNoise(b, 0.06f, 0.05f, 3000f, 9000f, 0.8f, 0f);
                break;
            case "sword":
                b = buf(1.2f);
                bandNoise(b, 0.0f, 0.18f, 3500f, 9000f, 1f, 0f);
                for (float f : new float[]{2100f, 3300f, 5200f}) tone(b, 0.02f, f, 0.9f, 0.25f);
                break;
            case "engine":
                b = buf(2f);
                engine(b, 0f, 2f);
                break;
            case "siren":
                b = buf(3f);
                for (int i = 0; i < 2; i++) {
                    sweep(b, i * 1.5f, 0.75f, 700f, 1000f, 0.4f);
                    sweep(b, i * 1.5f + 0.75f, 0.75f, 1000f, 700f, 0.4f);
                }
                break;
            case "drip":
                b = buf(1.4f);
                for (int i = 0; i < 3; i++) sweep(b, 0.05f + i * 0.45f, 0.07f, 1900f, 900f, 0.5f - i * 0.1f);
                break;
            case "fire":
                b = buf(2.6f);
                noise(b, 0.0f, 2.6f, 0.3f, 500f, 0.8f);
                Random fr = new Random(5);
                for (int i = 0; i < 40; i++) noise(b, fr.nextFloat() * 2.4f, 0.012f, 0.4f + fr.nextFloat() * 0.5f, 5000f, 0f);
                break;
            case "bird":
                b = buf(1.4f);
                for (int i = 0; i < 4; i++) {
                    sweep(b, 0.05f + i * 0.3f, 0.07f, 3000f + i * 150f, 4300f, 0.35f);
                    sweep(b, 0.13f + i * 0.3f, 0.06f, 4200f, 3300f, 0.3f);
                }
                break;
            case "dog":
                b = buf(1.1f);
                voice(b, 0.0f, 0.2f, 170f, 120f, 650f, 1050f, 1f, 0.5f, 0f);
                voice(b, 0.45f, 0.22f, 160f, 110f, 650f, 1050f, 1f, 0.5f, 0f);
                break;
            case "cat":
                b = buf(0.9f);
                voice(b, 0.0f, 0.75f, 420f, 650f, 500f, 2100f, 0.8f, 0.15f, 5f);
                break;
            default:
                return null;
        }
        short[] out = new short[b.length];
        float peak = 0.0001f;
        for (float v : b) peak = Math.max(peak, Math.abs(v));
        float g = Math.min(4f, 0.85f / peak); // every effect about as loud as the others at the volume chosen
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

    // ------------------------------------------------------------------ voices, whistles, engines

    /** A resonance at hz with the given width, applied to the whole signal. */
    private static float[] band(float[] x, float hz, float width) {
        double w0 = 2 * Math.PI * Math.min(hz, RATE * 0.45) / RATE, q = Math.max(0.5, hz / Math.max(40f, width));
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

    /**
     * A voiced sound ("ha", a scream, a bark, a meow): a buzzing tone whose pitch goes from f0a to f0b, shaped
     * by two resonances (the vowel), with some breath mixed in. vib: a wobble in the pitch, in Hz.
     */
    private static void voice(float[] b, float at, float len, float f0a, float f0b, float f1, float f2, float amp, float breath, float vib) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        if (s >= b.length) return;
        n = Math.min(n, b.length - s);
        float[] src = new float[n];
        Random r = new Random(s + 13);
        double ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            double f = f0a + (f0b - f0a) * t;
            if (vib > 0) f *= 1 + 0.03 * Math.sin(2 * Math.PI * vib * i / RATE);
            ph += f / RATE;
            float saw = (float) (ph - Math.floor(ph)) * 2 - 1;
            src[i] = saw * (1 - breath * 0.6f) + (r.nextFloat() * 2 - 1) * breath;
        }
        float[] y1 = band(src, f1, f1 * 0.18f + 60f), y2 = band(src, f2, f2 * 0.12f + 90f);
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float env = Math.min(1f, i / (RATE * 0.012f)) * (float) Math.pow(Math.sin(Math.PI * Math.pow(t, 0.7)), 0.6);
            b[s + i] += amp * env * 0.9f * (y1[i] + 0.6f * y2[i]);
        }
    }

    /** Noise between lo and hi Hz that swells in and out: breath, a gasp, a sneeze's start. peakAt: where in it the loudest part is (0..1). */
    private static void bandNoise(float[] b, float at, float len, float lo, float hi, float amp, float peakAt) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        if (s >= b.length) return;
        n = Math.min(n, b.length - s);
        Random r = new Random(s + 29);
        float[] src = new float[n];
        for (int i = 0; i < n; i++) src[i] = r.nextFloat() * 2 - 1;
        float[] y = band(src, (float) Math.sqrt(lo * hi), hi - lo);
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float env = peakAt <= 0.01f ? (float) Math.exp(-5.0 * t) * Math.min(1f, i / 40f)
                    : t < peakAt ? (float) Math.sin(0.5 * Math.PI * t / peakAt) : (float) Math.cos(0.5 * Math.PI * (t - peakAt) / (1 - peakAt));
            b[s + i] += amp * env * y[i] * 1.6f;
        }
    }

    /** A pure tone that glides from one pitch to another (a whistle, a drip, a bird, a siren). */
    private static void sweep(float[] b, float at, float len, float from, float to, float amp) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        double ph = 0;
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) n;
            ph += 2 * Math.PI * (from + (to - from) * t) / RATE;
            float env = Math.min(1f, i / 120f) * Math.min(1f, (n - i) / 160f);
            b[s + i] += amp * env * (float) Math.sin(ph);
        }
    }

    /** A car or motorcycle engine starting and idling: a low rough tone that rises and settles. */
    private static void engine(float[] b, float at, float len) {
        int s = (int) (at * RATE), n = (int) (len * RATE);
        Random r = new Random(17);
        double ph = 0;
        float y = 0;
        for (int i = 0; i < n && s + i < b.length; i++) {
            float t = i / (float) n;
            double f = 38 + 60 * Math.exp(-4 * t) * (t < 0.15 ? t / 0.15 : 1) + 6 * Math.sin(2 * Math.PI * 7 * t);
            ph += f / RATE;
            float pulse = (float) Math.pow(Math.max(0, Math.sin(2 * Math.PI * ph)), 3) * 2 - 0.6f;
            y += 0.12f * (pulse + (r.nextFloat() - 0.5f) * 0.6f - y);
            b[s + i] += 0.9f * y * Math.min(1f, i / 400f) * Math.min(1f, (n - i) / 3000f);
        }
    }
}
