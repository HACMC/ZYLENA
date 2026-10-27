package com.zylena.screenreader;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speaks language-tagged text with the phone's own text-to-speech (Google voices by default), picking a
 * female voice for English and for Tagalog.
 */
final class Speaker {
    interface Listener {
        void onSpeakingChanged(boolean speaking);
    }

    private static Speaker instance;

    static synchronized Speaker get(Context c) {
        if (instance == null) instance = new Speaker(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();

    private TextToSpeech tts;
    private boolean ttsReady;
    private Runnable whenReady;
    private final List<Runnable> pendingVoiceCallbacks = new ArrayList<>();

    private volatile int curRun; // index of the run being spoken, for pause, back and skip
    private volatile boolean paused;
    private List<TextPrep.Seg> pausedRuns;
    private int pausedAt;
    private boolean pausedTrack;
    private volatile long sleepAt; // when the sleep timer stops reading; 0 = off
    private volatile int generation; // bumped on every speak/stop so stale work quits quietly
    private volatile boolean speaking;
    private Listener listener;

    /** Tells the reader which screen lines are being spoken, for resume and highlighting. */
    interface Progress {
        /** along: how far into the paragraph this run starts, 0 to 1 (long paragraphs are read in several runs). */
        void onRun(int firstLine, int lastLine, float along);

        void onFinished();
    }

    private Progress progress;
    private volatile boolean tracking;
    private volatile List<TextPrep.Seg> currentRuns = new ArrayList<>();

    private Speaker(Context c) {
        ctx = c;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setProgress(Progress p) {
        progress = p;
    }

    private void runStarted(int i, int gen) {
        List<TextPrep.Seg> runs = currentRuns;
        if (gen != generation || i < 0 || i >= runs.size()) return;
        curRun = i;
        TextPrep.Seg r = runs.get(i);
        Logs.d(ctx, "speaking run " + i + "/" + runs.size() + " [" + r.lang + "] lines " + r.line + "-" + r.lastLine
                + ": " + Logs.snip(r.text));
        Progress p = progress;
        int before = 0, total = 0;
        for (int k = 0; k < runs.size(); k++) {
            TextPrep.Seg o = runs.get(k);
            if (o.line != r.line || o.lastLine != r.lastLine) continue;
            if (k < i) before += o.text.length();
            total += o.text.length();
        }
        float along = total == 0 ? 0f : before / (float) total;
        if (tracking && p != null) main.post(() -> p.onRun(r.line, r.lastLine, along));
    }

    private void allDone(int gen) {
        if (gen != generation) return;
        Logs.d(ctx, "finished reading");
        Progress p = progress;
        if (tracking && p != null) main.post(p::onFinished);
        setSpeaking(false);
    }

    boolean isSpeaking() {
        return speaking;
    }

    private void setSpeaking(boolean s) {
        if (speaking == s) return;
        speaking = s;
        Listener l = listener;
        if (l != null) main.post(() -> l.onSpeakingChanged(s));
    }

    // ---------------------------------------------------------------- public

    /** track = true for real screen reading (reports progress); false for samples and tests. */
    void speak(List<TextPrep.Seg> segs, boolean track) {
        stop();
        if (segs.isEmpty()) return;
        curRun = 0;
        final int gen = ++generation;
        tracking = track;
        setSpeaking(true);
        Logs.d(ctx, "speak " + segs.size() + " pieces, track=" + track);
        // One run per screen line, with a short breath between paragraphs.
        List<TextPrep.Seg> runs = TextPrep.merge(segs, 350, 350, false, true);
        currentRuns = runs;
        speakSystem(runs, gen);
    }

    /** Plays a sample in one specific phone voice, for the voice picker. */
    void preview(Voice v, String text) {
        stop();
        final int gen = ++generation;
        tracking = false;
        setSpeaking(true);
        ensureTts(() -> {
            if (gen != generation) return;
            tts.setSpeechRate(Prefs.rate(ctx));
            tts.setPitch(Prefs.pitch(ctx));
            tts.setVoice(v);
            String l = v.getLocale().getLanguage();
            tts.speak(TextPrep.sayName(text, l.equals("fil") || l.equals("tl") ? TextPrep.FIL : TextPrep.EN),
                    TextToSpeech.QUEUE_FLUSH, null, gen + ":0:last");
        });
    }

    /** Reads text the user copied, with the same smart rules as the screen. */
    void readCopied(String text) {
        List<String> lines = new ArrayList<>();
        for (String l : text.split("\n")) if (!l.trim().isEmpty()) lines.add(l);
        Logs.d(ctx, "reading copied text: " + lines.size() + " lines");
        speak(TextPrep.prepare(lines, Prefs.skipTimestamps(ctx), Prefs.skipRefs(ctx), Prefs.readVerses(ctx)), false);
    }

    void speakText(String text) {
        List<String> one = new ArrayList<>();
        one.add(text);
        speak(TextPrep.prepare(one, false), false);
    }

    void stop() {
        generation++;
        paused = false;
        if (tts != null) tts.stop();
        setSpeaking(false);
    }

    boolean isPaused() {
        return paused;
    }

    /** Stops for now but keeps the place; resume() continues from the paragraph that was being read. */
    void pause() {
        if (!speaking) return;
        List<TextPrep.Seg> runs = currentRuns;
        int at = Math.max(0, Math.min(curRun, runs.size() - 1));
        boolean track = tracking;
        stop();
        pausedRuns = runs;
        pausedAt = at;
        pausedTrack = track;
        paused = true;
        Logs.d(ctx, "paused at run " + at);
        Listener l = listener;
        if (l != null) main.post(() -> l.onSpeakingChanged(false));
    }

    void resume() {
        if (!paused || pausedRuns == null || pausedRuns.isEmpty()) return;
        Logs.d(ctx, "resume at run " + pausedAt);
        speak(new ArrayList<>(pausedRuns.subList(pausedAt, pausedRuns.size())), pausedTrack);
    }

    /** Jumps back (-1) or ahead (+1) a paragraph while reading. */
    void skip(int delta) {
        if (paused) {
            pausedAt = Math.max(0, Math.min(pausedAt + delta, pausedRuns.size() - 1));
            resume();
            return;
        }
        if (!speaking) return;
        List<TextPrep.Seg> runs = currentRuns;
        int at = curRun + delta;
        if (at >= runs.size()) {
            stop();
            return;
        }
        speak(new ArrayList<>(runs.subList(Math.max(0, at), runs.size())), tracking);
    }

    // ------------------------------------------------------------ sleep timer

    private final Runnable sleepNow = this::sleepNow;

    private void sleepNow() {
        Logs.d(ctx, "sleep timer: stopped reading");
        sleepAt = 0;
        stop();
        toast("Sleep timer: reading stopped. Good night!");
    }

    /** Stops reading after this many minutes; 0 turns the timer off. */
    void setSleepTimer(int minutes) {
        main.removeCallbacks(sleepNow);
        sleepAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        if (minutes > 0) main.postDelayed(sleepNow, minutes * 60_000L);
        Logs.d(ctx, "sleep timer: " + (minutes > 0 ? minutes + " min" : "off"));
    }

    /** Minutes left on the sleep timer, 0 when off. */
    int sleepMinutesLeft() {
        long left = sleepAt - System.currentTimeMillis();
        return sleepAt == 0 || left <= 0 ? 0 : (int) Math.ceil(left / 60_000.0);
    }

    // ------------------------------------------------------- system engine

    private void ensureTts(Runnable then) {
        if (ttsReady) {
            then.run();
            return;
        }
        whenReady = then;
        if (tts != null) return; // still starting; whenReady runs when it is up
        String pkg = Prefs.ttsPackage(ctx);
        tts = new TextToSpeech(ctx, status -> main.post(() -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                Logs.d(ctx, "text-to-speech engine ready: " + tts.getDefaultEngine());
                tts.setAudioAttributes(attrs);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        try {
                            String[] p = id.split(":");
                            runStarted(Integer.parseInt(p[1]), Integer.parseInt(p[0]));
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void onDone(String id) {
                        if (id.startsWith("save:")) {
                            if (id.endsWith(":last")) saveFinished();
                            return;
                        }
                        if (id.endsWith(":last")) allDone(Integer.parseInt(id.split(":")[0]));
                    }

                    @Override
                    public void onError(String id) {
                        Logs.d(ctx, "TTS error on " + id);
                        if (id.startsWith("save:")) {
                            if (id.endsWith(":last")) saveFinished();
                            return;
                        }
                        if (id.endsWith(":last")) allDone(Integer.parseInt(id.split(":")[0]));
                    }
                });
                Runnable r = whenReady;
                whenReady = null;
                if (r != null) r.run();
                for (Runnable c : pendingVoiceCallbacks) c.run();
                pendingVoiceCallbacks.clear();
            } else {
                Logs.d(ctx, "text-to-speech engine failed to start, status=" + status);
                tts = null;
                whenReady = null;
                setSpeaking(false);
                toast("No text-to-speech engine found on this phone.");
            }
        }), pkg.isEmpty() ? null : pkg);
    }

    private void speakSystem(List<TextPrep.Seg> runs, int gen) {
        if (Prefs.testMode(ctx)) {
            simulate(runs, gen, 0);
            return;
        }
        // Translate: only the screen's own text, not ZYLENA's messages and samples.
        boolean translate = tracking && Prefs.translate(ctx);
        ensureTts(() -> {
            if (gen != generation) return;
            tts.setSpeechRate(Prefs.rate(ctx));
            tts.setPitch(Prefs.pitch(ctx));
            tts.stop();
            if (translate) {
                translateNext(runs, gen, 0);
                return;
            }
            for (int i = 0; i < runs.size(); i++) queue(runs, gen, i);
        });
    }

    /**
     * Translates run i, queues it, then goes on with the next one. The voice reads run i while run i+1 is
     * being translated, so only the very first one is waited for. A run that can't be translated is read
     * as it is.
     */
    private void translateNext(List<TextPrep.Seg> runs, int gen, int i) {
        if (gen != generation || i >= runs.size()) return;
        TextPrep.Seg s = runs.get(i);
        if (s.translated) {
            queue(runs, gen, i);
            translateNext(runs, gen, i + 1);
            return;
        }
        Translate.text(ctx, s.text, s.lang, out -> main.post(() -> {
            if (gen != generation) return;
            if (out != null && !out.trim().isEmpty()) {
                String to = Translate.other(s.lang);
                String said = to.equals(TextPrep.FIL) ? TextPrep.sayTagalog(out) : out;
                runs.set(i, new TextPrep.Seg(said, to, s.line, s.lastLine, s.heading, true));
            } else if (i == 0) {
                toast("Couldn't translate. Reading as it is. The translation pack needs internet the first time.");
            }
            queue(runs, gen, i);
            translateNext(runs, gen, i + 1);
        }));
    }

    /** Puts run i in the voice's queue, with a breath before it when a new paragraph starts. */
    private void queue(List<TextPrep.Seg> runs, int gen, int i) {
        TextPrep.Seg s = runs.get(i);
        applyVoice(s.lang);
        Voice used = chosenVoice(s.lang);
        if (used != null && used.isNetworkConnectionRequired()) Net.spokeOnline(ctx, s.text.length());
        if (i == 0 || !runs.get(i - 1).lang.equals(s.lang)) {
            Logs.d(ctx, "voice for " + s.lang + ": " + (used == null ? "(default locale)" : used.getName()));
        }
        boolean last = i == runs.size() - 1;
        if (i > 0 && runs.get(i - 1).lastLine != s.line) {
            // A person takes a breath between paragraphs, and a longer one around a heading.
            boolean head = s.heading || runs.get(i - 1).heading;
            tts.playSilentUtterance(head ? HEADING_PAUSE_MS : PARAGRAPH_PAUSE_MS, TextToSpeech.QUEUE_ADD, gen + ":-1");
        }
        tts.setSpeechRate(rateFor(s.lang));
        // Voice and language switch between runs, so each run is queued as its own utterance.
        tts.speak(TextPrep.sayName(s.text, s.lang), TextToSpeech.QUEUE_ADD, null, gen + ":" + i + (last ? ":last" : ""));
    }

    private static final long PARAGRAPH_PAUSE_MS = 320, HEADING_PAUSE_MS = 650;

    /** Emulator test: there is no voice there, so each piece takes about as long as reading it would. */
    private void simulate(List<TextPrep.Seg> runs, int gen, int i) {
        if (gen != generation) return;
        if (i >= runs.size()) {
            allDone(gen);
            return;
        }
        runStarted(i, gen);
        long ms = 300 + runs.get(i).text.length() * 1000L / 45;
        main.postDelayed(() -> simulate(runs, gen, i + 1), ms);
    }

    private float rateFor(String lang) {
        return Prefs.rate(ctx) * (lang.equals(TextPrep.FIL) ? Prefs.rateFil(ctx) : 1f);
    }

    /** Sets the engine's voice for the next utterance: the chosen one, else the best female voice. */
    private void applyVoice(String lang) {
        Voice v = chosenVoice(lang);
        if (v != null) {
            tts.setVoice(v);
        } else {
            tts.setLanguage(lang.equals(TextPrep.FIL) ? new Locale("fil", "PH") : Locale.US);
        }
    }

    private Voice chosenVoice(String lang) {
        String name = Prefs.voice(ctx, lang);
        List<Voice> all = voicesFor(lang);
        Voice pick = all.isEmpty() ? null : all.get(0);
        if (!name.isEmpty()) {
            for (Voice v : all) if (v.getName().equals(name)) pick = v;
        }
        return twin(pick, all);
    }

    /**
     * Google's voices come in pairs: "...-network" (online, sounds better) and "...-local" (on the phone).
     * With internet and "Use internet" on: the online one; otherwise the one on the phone.
     */
    private Voice twin(Voice v, List<Voice> all) {
        if (v == null) return null;
        String n = v.getName();
        boolean on = Net.online(ctx);
        String want = on && n.endsWith("-local") ? n.substring(0, n.length() - 6) + "-network"
                : !on && n.endsWith("-network") ? n.substring(0, n.length() - 8) + "-local" : null;
        if (want == null) return v;
        for (Voice o : all) if (o.getName().equals(want)) return o;
        return v;
    }

    /** Installed voices for "en" or "fil", most likely female and best quality first. */
    List<Voice> voicesFor(String lang) {
        List<Voice> out = new ArrayList<>();
        if (tts == null || !ttsReady || tts.getVoices() == null) return out;
        for (Voice v : tts.getVoices()) {
            String l = v.getLocale().getLanguage();
            boolean match = lang.equals(TextPrep.FIL) ? (l.equals("fil") || l.equals("tl")) : l.equals("en");
            if (match && !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) out.add(v);
        }
        out.sort((a, b) -> femaleScore(b, lang) - femaleScore(a, lang));
        return out;
    }

    /**
     * Android doesn't say a voice's gender, so this guesses: "female" in the name, then the codes Google
     * uses for its female voices, then higher quality and the right country. The user can override it.
     */
    private static int femaleScore(Voice v, String lang) {
        String n = v.getName().toLowerCase(Locale.ROOT);
        int s = v.getQuality() / 100;
        if (n.contains("female")) s += 100;
        if (n.contains("male") && !n.contains("female")) s -= 100;
        for (String code : FEMALE_CODES) if (n.contains("-x-" + code)) s += 50;
        for (String code : MALE_CODES) if (n.contains("-x-" + code)) s -= 50;
        // The owner's pick for English: Google's en-US "tpc" network voice, then its offline twin if there's no internet.
        if (lang.equals(TextPrep.EN) && n.equals("en-us-x-tpc-network")) s += 1000;
        if (lang.equals(TextPrep.EN) && n.equals("en-us-x-tpc-local")) s += 900;
        String country = v.getLocale().getCountry();
        if (lang.equals(TextPrep.EN) && country.equals("US")) s += 5;
        if (lang.equals(TextPrep.FIL) && country.equals("PH")) s += 5;
        return s;
    }

    private static final java.util.List<String> FEMALE_CODES = java.util.Arrays.asList("tpf", "sfg", "tpc", "iog", "fie", "fic", "cfc");
    private static final java.util.List<String> MALE_CODES = java.util.Arrays.asList("iol", "iom", "tpd", "fid", "fia", "cmh");
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("-x-([a-z]{3})-(?:local|network)");

    /**
     * Android gives voices only codes like "en-us-x-tpc-network". Make that readable: "Voice TPC · likely female".
     * Gender is a best guess from the codes Google is known to use for its voices; it isn't published by the API.
     */
    static String friendly(Voice v) {
        String n = v.getName().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = CODE.matcher(n);
        if (!m.find()) return n.contains("language") ? "Standard voice (" + v.getLocale().toLanguageTag() + ")" : v.getName();
        String code = m.group(1);
        String gender = FEMALE_CODES.contains(code) ? " · likely female" : MALE_CODES.contains(code) ? " · likely male" : "";
        return "Voice " + code.toUpperCase(Locale.ROOT) + gender;
    }

    // ------------------------------------------------------------ save as audio

    private java.io.File saveDir;
    private int saveCount;

    /** Speaks the text into a WAV file in Downloads (Music folder on older phones) instead of out loud. */
    void saveAudio(List<TextPrep.Seg> segs) {
        stop();
        if (segs.isEmpty()) return;
        List<TextPrep.Seg> runs = TextPrep.merge(segs, 350, 350, false, true);
        toast("Saving the reading as an audio file\u2026");
        ensureTts(() -> {
            saveDir = new java.io.File(ctx.getCacheDir(), "save");
            java.io.File[] old = saveDir.listFiles();
            if (old != null) for (java.io.File f : old) f.delete();
            saveDir.mkdirs();
            saveCount = runs.size();
            tts.setPitch(Prefs.pitch(ctx));
            for (int i = 0; i < runs.size(); i++) {
                TextPrep.Seg s = runs.get(i);
                applyVoice(s.lang);
                tts.setSpeechRate(rateFor(s.lang));
                String id = "save:" + i + (i == runs.size() - 1 ? ":last" : "");
                tts.synthesizeToFile(TextPrep.sayName(s.text, s.lang), null, new java.io.File(saveDir, String.format(Locale.US, "%04d.wav", i)), id);
            }
            Logs.d(ctx, "saving " + runs.size() + " pieces to audio");
        });
    }

    private void saveFinished() {
        new Thread(() -> {
            try {
                String where = Audio.join(ctx, saveDir, saveCount);
                Logs.d(ctx, "audio saved: " + where);
                toast("Audio saved: " + where);
            } catch (Exception e) {
                Logs.d(ctx, "saving audio failed: " + e);
                toast("Couldn't save the audio: " + e.getMessage());
            }
        }, "zylena-save").start();
    }

    /** Text-to-speech apps installed on the phone (Google, Samsung, and any others the user added). */
    List<TextToSpeech.EngineInfo> phoneEngines() {
        List<TextToSpeech.EngineInfo> out = new ArrayList<>();
        if (tts != null && ttsReady) out.addAll(tts.getEngines());
        return out;
    }

    /** Switches the phone voice to another text-to-speech app; then runs r once it is up. */
    void usePhoneEngine(String pkg, Runnable r) {
        stop();
        Prefs.setTtsPackage(ctx, pkg);
        if (tts != null) tts.shutdown();
        tts = null;
        ttsReady = false;
        Logs.d(ctx, "phone voice app: " + (pkg.isEmpty() ? "default" : pkg));
        whenSystemReady(r);
    }

    /** Runs once the system engine is up, e.g. to fill the voice pickers. */
    void whenSystemReady(Runnable r) {
        if (ttsReady) {
            r.run();
            return;
        }
        pendingVoiceCallbacks.add(r);
        if (tts == null) ensureTts(() -> { });
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }
}
