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

    /** Where in a run the sound effect plays: the number of the piece (a character's words come first, then "she laughed"). */
    private final java.util.Map<Integer, Integer> sfxAt = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int curRun; // index of the run being spoken, for pause, back and skip
    /** Where in the spoken run the voice is: per utterance {where it starts in the run, its share of the run, its length}. */
    private final java.util.Map<String, float[]> partSpan = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int posRun = -1;
    private volatile float posFrac;
    private volatile boolean rangeSeen;
    private volatile long runStartedAt;
    private volatile boolean paused;
    private List<TextPrep.Seg> pausedRuns;
    private int pausedAt;
    private boolean pausedTrack;
    private volatile long sleepAt; // when the sleep timer stops reading; 0 = off
    private volatile int generation; // bumped on every speak/stop so stale work quits quietly
    private final java.util.Set<String> bibleVoiceLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int announcedGen = -1; // "Announce who is speaking": the reading and the character last named in it
    private String announced;
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
        VoiceFx.loadCustom(c);
        // Recordings of styled lines left from before (ZYLENA was closed while it read, or a piece was given up): of no use now.
        try {
            java.io.File[] old = c.getCacheDir().listFiles((dir, name) -> name.startsWith("deep-") && name.endsWith(".wav"));
            if (old != null) for (java.io.File f : old) f.delete();
        } catch (Exception ignored) {
        }
    }

    /** Shows or hides (null) a short note on screen while the reader waits for something. */
    interface Notice {
        void show(String text);
    }

    private Notice notice;
    private Runnable waitTick;
    private boolean noticeUp;

    void setNotice(Notice n) {
        notice = n;
    }

    /** While a translation is awaited and the voice has nothing to say: a note and a soft tick, so it's clear it is working. */
    private void startWait(int gen, long askedAt) {
        endWait();
        // The longest this translation can take: the AI gets a fixed time, then the quick translator answers.
        final long until = askedAt + Translate.longestMs(ctx);
        waitTick = new Runnable() {
            int ticks;

            @Override
            public void run() {
                if (waitTick != this || gen != generation) return;
                boolean silent = (tts == null || !tts.isSpeaking()) && (gemini == null || !gemini.isPlaying());
                if (silent) {
                    // Counts down all the way, from the most it can take: 20, 19 … 3, 2, 1.
                    long left = (until - System.currentTimeMillis() + 999) / 1000;
                    if (notice != null) notice.show(left > 0 ? "Translating… " + left : "Translating…");
                    noticeUp = true;
                    if (ticks++ % 3 == 0) tick();
                } else if (noticeUp) {
                    if (notice != null) notice.show(null);
                    noticeUp = false;
                }
                main.postDelayed(this, 1000);
            }
        };
        main.postDelayed(waitTick, 1500);
    }

    private void endWait() {
        if (waitTick != null) main.removeCallbacks(waitTick);
        waitTick = null;
        if (noticeUp && notice != null) notice.show(null);
        noticeUp = false;
    }

    private android.media.ToneGenerator toner;

    private void tick() {
        try {
            if (toner == null) toner = new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 25);
            toner.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 60);
        } catch (Exception ignored) {
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setProgress(Progress p) {
        progress = p;
    }

    /** A piece of run i began to be said: the run's sound effect plays at its piece. */
    private void partStarted(int i, int k, int gen) {
        List<TextPrep.Seg> runs = currentRuns;
        if (gen != generation || i < 0 || i >= runs.size()) return;
        // The sound effect is not played from here: the reading is held for it (see sfxNow), so it is heard clearly.
    }

    /** The effects to play when their silence starts: utterance id -> effect. Reading is held for each one. */
    private final java.util.Map<String, String> sfxNow = new java.util.concurrent.ConcurrentHashMap<>();
    /** The run each kind of effect last played in, so that the same sound is not held for again and again. */
    private final java.util.Map<String, Integer> sfxLastRun = new java.util.concurrent.ConcurrentHashMap<>();

    private int startedGen = -1, startedRun = -1;

    private void runStarted(int i, int gen) {
        List<TextPrep.Seg> runs = currentRuns;
        if (gen != generation || i < 0 || i >= runs.size()) return;
        // Once per run: the rest of a long line read the plain way, after its first piece was played, is not a new start.
        if (gen == startedGen && i == startedRun) return;
        startedGen = gen;
        startedRun = i;
        if (sleepFinishing && i > sleepRun) {
            sleepFinishing = false;
            main.removeCallbacks(sleepForce);
            Logs.d(ctx, "sleep timer: stopped reading after the paragraph");
            main.post(() -> {
                stop();
                toast("Sleep timer: reading stopped. Good night!");
            });
            return;
        }
        curRun = i;
        posRun = i;
        posFrac = 0f;
        rangeSeen = false;
        runStartedAt = System.currentTimeMillis();
        if (!translating && !Prefs.testMode(ctx)) main.post(() -> queueAhead(runs, gen, i));
        TextPrep.Seg r = runs.get(i);
        Logs.d(ctx, "speaking run " + i + "/" + runs.size() + " [" + r.lang + "] lines " + r.line + "-" + r.lastLine
                + ": " + (r.translated ? (r.text.length() > 600 ? r.text.substring(0, 600) + "…" : r.text)
                : Logs.snip(Bible.sayRefs(r.text, r.lang.equals(TextPrep.FIL)))));
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

    /** The words of the piece being spoken (or paused at), exactly as the voice gets them; null when nothing is read. */
    String currentSpoken() {
        java.util.ArrayList<String> page = pageSpoken();
        return page.isEmpty() ? null : page.get(pageAt());
    }

    /** The words of the piece being read (or paused at), as on the page, for the app's player card; null when nothing is. */
    String nowText() {
        List<TextPrep.Seg> runs = paused && pausedRuns != null ? pausedRuns : currentRuns;
        if ((!speaking && !paused) || runs == null || runs.isEmpty()) return null;
        int i = Math.max(0, Math.min(paused ? pausedAt : curRun, runs.size() - 1));
        String t = TextPrep.unmark(runs.get(i).text).trim();
        return t.isEmpty() ? null : t;
    }

    /** Every piece of the page being read (or last read), as the voice gets it. Empty when nothing was read yet. */
    java.util.ArrayList<String> pageSpoken() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        List<TextPrep.Seg> runs = paused && pausedRuns != null ? pausedRuns : currentRuns;
        if (runs == null) return out;
        for (TextPrep.Seg r : runs) {
            StringBuilder sb = new StringBuilder();
            for (String[] part : voiceParts(r)) sb.append(sb.length() > 0 ? " " : "").append(TextPrep.sayName(part[0], part[1]));
            out.add(sb.toString());
        }
        return out;
    }

    /** The language of each of those pieces. */
    java.util.ArrayList<String> pageLangs() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        List<TextPrep.Seg> runs = paused && pausedRuns != null ? pausedRuns : currentRuns;
        if (runs != null) for (TextPrep.Seg r : runs) out.add(r.lang);
        return out;
    }

    /** Says a few words right now in the usual voice of that language, to try a way of saying them. Reading stays paused. */
    void sayNow(String text, String lang) {
        if (speaking || text == null || text.trim().isEmpty()) return;
        ensureTts(() -> {
            applyVoice(lang);
            tts.setSpeechRate(rateFor(lang));
            tts.setPitch(Prefs.pitch(ctx));
            tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, "try");
        });
    }

    /** Which of those pieces is being read (or was, when it stopped). */
    int pageAt() {
        List<TextPrep.Seg> runs = paused && pausedRuns != null ? pausedRuns : currentRuns;
        int n = runs == null ? 0 : runs.size();
        return Math.max(0, Math.min(paused ? pausedAt : curRun, n - 1));
    }

    /** The piece being spoken is the last one of what was given to read. */
    boolean onLastRun() {
        List<TextPrep.Seg> runs = currentRuns;
        return speaking && !runs.isEmpty() && curRun >= runs.size() - 1;
    }

    /** About how long the piece being spoken takes, from its length and the reading speed. */
    long currentRunMs() {
        List<TextPrep.Seg> runs = currentRuns;
        if (runs.isEmpty()) return 0;
        TextPrep.Seg r = runs.get(Math.max(0, Math.min(curRun, runs.size() - 1)));
        return (long) (r.text.length() * 1000L / (11f * Math.max(0.3f, rateFor(r.lang))));
    }

    /**
     * For the "sounded wrong" mark: everything the reader decided for the sentence being said (and the one before), so what sounded
     * wrong can be found and fixed, with no need to describe it.
     */
    String describeCurrent() {
        List<TextPrep.Seg> rs = currentRuns;
        if (rs == null || rs.isEmpty()) return "nothing is being read";
        int i = Math.max(0, Math.min(curRun, rs.size() - 1));
        StringBuilder sb = new StringBuilder();
        if (i > 0) sb.append("before: ").append(rs.get(i - 1).text.replace('\n', ' ')).append(" || ");
        TextPrep.Seg s = rs.get(i);
        sb.append("run ").append(i).append(" [").append(s.lang).append("] voices=").append(s.cast == null ? "none" : java.util.Arrays.toString(s.cast))
                .append(" who=").append(s.who).append(" mood=").append(s.mood).append(" narrFeel=").append(s.narrFeel).append(" sfx=").append(s.sfx)
                .append(" story=").append(s.story).append(" scene=").append(s.scene).append(" ref=").append(s.ref)
                .append(" | settings: fx=").append(Prefs.flag(ctx, "voice_fx", true)).append(" natural=").append(Prefs.flag(ctx, "natural", true))
                .append(" sfx=").append(Prefs.flag(ctx, "sfx", true)).append(" rate=").append(Prefs.rate(ctx)).append(" pitch=").append(Prefs.pitch(ctx))
                .append(" || text: ").append(s.text.replace('\n', ' '));
        return sb.toString();
    }

    /** The first paragraphs of what will be read next are translated ahead, so reading can start at once. */
    void warm(List<TextPrep.Seg> segs) {
        if (!Prefs.translate(ctx) || segs.isEmpty() || Prefs.testMode(ctx)) return;
        List<TextPrep.Seg> runs = TextPrep.merge(segs, 350, 900, false, true);
        int i = 0;
        for (int n = 0; n < 2 && i < runs.size(); ) {
            TextPrep.Seg s = runs.get(i);
            if (inTarget(s) || runs.get(groupEnd(runs, i)).ref != null) {
                i++;
                continue;
            }
            int last = groupEnd(runs, i);
            StringBuilder all = new StringBuilder();
            for (int k = i; k <= last; k++) all.append(k > i ? " " : "").append(runs.get(k).text);
            Translate.text(ctx, TextPrep.unmark(all.toString()), s.lang, "", out -> {
            });
            i = last + 1;
            n++;
        }
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
        partSpan.clear();
        sfxAt.clear();
        sfxNow.clear();
        sfxLastRun.clear();
        final int gen = ++generation;
        tracking = track;
        setSpeaking(true);
        Logs.d(ctx, "speak " + segs.size() + " pieces, track=" + track + ", app " + (TextPrep.faithApp ? "religious" : "story")
                + ", Bible voice " + (TextPrep.bibleVoice ? "on" : "off") + ", announce who is speaking " + (Prefs.flag(ctx, "announce_speaker", false) ? "on" : "off"));
        bibleVoiceLogged.clear();
        // One run per screen line, with a short breath between paragraphs. A whole paragraph goes to the voice
        // in one piece where it can: the voice waits a moment before each piece, which sounds wrong mid-paragraph.
        List<TextPrep.Seg> runs = TextPrep.merge(segs, 350, 900, false, true);
        currentRuns = runs;
        castLogged = false;
        castLoggedFor.clear();
        noManLogged.clear();
        speakSystem(runs, gen);
    }

    /** Plays a sample in one of Gemini's voices, for the Gemini voice picker. */
    void previewGemini(String voice) {
        stop();
        String why = GeminiVoice.whyNot(ctx);
        if (why != null) {
            toast(why);
            return;
        }
        final int gen = ++generation;
        tracking = false;
        currentRuns = new ArrayList<>();
        setSpeaking(true);
        GeminiVoice.Item it = new GeminiVoice.Item(gen, 0, "Magandang araw! Ako si Zelina. Babasahin ko nang malinaw ang inyong aralin. "
                + "Hello! I will read your lesson to you in English too.", true, 0, Prefs.rate(ctx));
        it.voice = voice;
        Logs.d(ctx, "Gemini voice test: " + voice);
        gemini().add(it);
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
            lastSet = v;
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
        boolean wasPaused = paused;
        generation++;
        endWait();
        paused = false;
        sleepFinishing = false;
        main.removeCallbacks(sleepForce);
        stopDeep();
        if (tts != null) tts.stop();
        if (gemini != null) gemini.stop();
        handLate.clear();
        setSpeaking(false);
        // Stopped while paused: "speaking" was false already, so say it here, or the notification stayed "ZYLENA paused".
        Listener l = listener;
        if (wasPaused && l != null) main.post(() -> l.onSpeakingChanged(false));
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
        // The sentence being read is where reading goes on from, not the start of the whole paragraph.
        TextPrep.Seg tail = tailOf(runs.get(at));
        stop();
        pausedRuns = runs;
        if (tail != null) {
            pausedRuns = new ArrayList<>(runs);
            pausedRuns.set(at, tail);
        }
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

    /**
     * After "Fix a word": reading goes on from the word that was fixed (the nearest place it is, at or before the
     * paragraph it stopped in), so the fix is heard at once and nothing before it is read again.
     */
    void resumeFrom(String word) {
        if (!paused || pausedRuns == null || pausedRuns.isEmpty() || word == null || word.trim().isEmpty()) {
            resume();
            return;
        }
        java.util.regex.Pattern w = java.util.regex.Pattern.compile("(?<![\\p{L}\\d])" + java.util.regex.Pattern.quote(word.trim()) + "(?![\\p{L}\\d])",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
        int found = -1, pos = -1;
        // The paragraph it stopped in first, then the ones before it (the word was just heard), then the ones after.
        for (int d = 0; d < pausedRuns.size() && found < 0; d++) {
            for (int i : new int[]{pausedAt - d, pausedAt + d}) {
                if (found >= 0 || i < 0 || i >= pausedRuns.size() || (d > 0 && i == pausedAt)) continue;
                java.util.regex.Matcher m = w.matcher(pausedRuns.get(i).text);
                if (m.find()) {
                    found = i;
                    pos = m.start();
                }
            }
        }
        if (found < 0) {
            resume();
            return;
        }
        List<TextPrep.Seg> from = new ArrayList<>(pausedRuns.subList(found, pausedRuns.size()));
        TextPrep.Seg cut = pos > 0 && from.get(0).text.indexOf('\u000E') < 0 ? cutAt(from.get(0), pos) : null;
        if (cut != null) from.set(0, cut);
        Logs.d(ctx, "going on from the fixed word \"" + word.trim() + "\" (run " + found + ")");
        speak(from, pausedTrack);
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
        List<TextPrep.Seg> from = new ArrayList<>(runs.subList(Math.max(0, at), runs.size()));
        // No jump (delta 0, e.g. the speed was changed): go on from the sentence being read.
        if (delta == 0 && at >= 0) {
            TextPrep.Seg tail = tailOf(runs.get(at));
            if (tail != null) from.set(0, tail);
        }
        speak(from, tracking);
    }

    /** How much of the run being read has been said, 0..1: from the voice's own progress, else from the time. */
    private float spokenFraction(TextPrep.Seg r) {
        if (rangeSeen && posRun == curRun) return posFrac;
        long el = System.currentTimeMillis() - runStartedAt;
        if (el < 1500) return 0f;
        float cps = 13f * Prefs.rate(ctx) * (r.lang.equals(TextPrep.FIL) ? Prefs.rateFil(ctx) : 1f);
        return Math.min(0.9f, el / 1000f * cps / Math.max(1, r.text.length()));
    }

    private static final java.util.regex.Pattern SENTENCE_STOP = java.util.regex.Pattern.compile("[.!?\u2026]+[\"\u201D\u2019')\\]]*\\s+");

    /**
     * The rest of the run being read, from the start of the sentence the voice is in, or null when the run is
     * to be read whole (nothing said yet, or it holds a Bible verse that must not be cut).
     */
    private TextPrep.Seg tailOf(TextPrep.Seg r) {
        if (r.text.indexOf('\u000E') >= 0) return null;
        float f = spokenFraction(r);
        if (f < 0.05f) return null;
        int pos = (int) (f * r.text.length()), cut = 0;
        java.util.regex.Matcher m = SENTENCE_STOP.matcher(r.text);
        while (m.find() && m.end() <= pos) if (r.text.length() - m.end() >= 15) cut = m.end();
        if (cut == 0) return null;
        Logs.d(ctx, "going on from the sentence being read (" + Math.round(f * 100) + "% of the paragraph said)");
        return cutAt(r, cut);
    }

    /** The run from this place in its text on, with the voices of the quotations still to come. */
    private TextPrep.Seg cutAt(TextPrep.Seg r, int cut) {
        TextPrep.Seg t = new TextPrep.Seg(r.text.substring(cut).trim(), r.lang, r.line, r.lastLine, r.heading, r.translated).like(r);
        t.sfx = "";
        if (r.cast != null) {
            int closed = 0;
            List<int[]> spans = Cast.quoteSpans(r.text.substring(0, cut), r.inQuote);
            for (int[] q : spans) closed += q[2];
            t.castFrom = r.castFrom + closed;
            t.inQuote = !spans.isEmpty() && spans.get(spans.size() - 1)[2] == 0;
        }
        return t;
    }

    // ------------------------------------------------------------ sleep timer

    private final Runnable sleepNow = this::sleepNow;

    private volatile boolean sleepFinishing;
    private volatile int sleepRun = -1;

    private void sleepNow() {
        sleepAt = 0;
        // Not in the middle of a paragraph: the one being read is finished, then reading stops.
        if (speaking && Prefs.flag(ctx, "sleep_finish", true) && curRun < currentRuns.size() - 1) {
            Logs.d(ctx, "sleep timer: finishing the paragraph first");
            sleepFinishing = true;
            sleepRun = curRun;
            main.postDelayed(sleepForce, 3 * 60_000L); // never longer than this
            return;
        }
        Logs.d(ctx, "sleep timer: stopped reading");
        stop();
        toast("Sleep timer: reading stopped. Good night!");
    }

    private final Runnable sleepForce = this::sleepForceNow;

    private void sleepForceNow() {
        if (!sleepFinishing) return;
        sleepFinishing = false;
        Logs.d(ctx, "sleep timer: stopped reading");
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
        final TextToSpeech[] made = new TextToSpeech[1];
        made[0] = tts = new TextToSpeech(ctx, status -> main.post(() -> {
            // Another voice app was chosen while this one was starting: this one is let go, the new one answers for itself.
            if (tts != made[0]) {
                try {
                    made[0].shutdown();
                } catch (Exception ignored) {
                }
                return;
            }
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                Logs.d(ctx, "text-to-speech engine ready: " + tts.getDefaultEngine());
                tts.setAudioAttributes(attrs);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        if (id != null && id.startsWith("deep:")) {
                            main.post(() -> deepStarted(id));
                            return;
                        }
                        try {
                            String[] p = id.split(":");
                            if (p.length > 2 && p[2].equals("x")) {
                                String fx = sfxNow.remove(id);
                                if (fx != null && Integer.parseInt(p[0]) == generation) Sfx.play(ctx, fx, true);
                                return;
                            }
                            int piece = p.length > 2 && p[2].startsWith("p") ? Integer.parseInt(p[2].substring(1)) : 0;
                            partStarted(Integer.parseInt(p[1]), piece, Integer.parseInt(p[0]));
                            // A run spoken in several pieces (two voices) starts once, with its first piece.
                            if (p.length > 2 && p[2].startsWith("p") && !p[2].equals("p0")) return;
                            runStarted(Integer.parseInt(p[1]), Integer.parseInt(p[0]));
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void onRangeStart(String id, int start, int end, int frame) {
                        try {
                            float[] sp = partSpan.get(id);
                            if (sp == null || sp[2] <= 0) return;
                            posRun = Integer.parseInt(id.split(":")[1]);
                            posFrac = Math.min(1f, sp[0] + sp[1] * start / sp[2]);
                            rangeSeen = true;
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void onDone(String id) {
                        if (id.startsWith("deep:")) {
                            main.post(() -> deepMarker(id, false, ""));
                            return;
                        }
                        if (id.startsWith("save:")) {
                            if (id.endsWith(":last")) saveFinished();
                            return;
                        }
                        if (id.endsWith(":last")) allDone(Integer.parseInt(id.split(":")[0]));
                    }

                    @Override
                    public void onError(String id, int code) {
                        ttsFailed(id, ttsError(code));
                    }

                    @Override
                    public void onError(String id) {
                        ttsFailed(id, "");
                    }

                    private void ttsFailed(String id, String why) {
                        Logs.d(ctx, "TTS error on " + id + (why.isEmpty() ? "" : ": " + why));
                        if (id == null) return;
                        if (id.startsWith("deep:")) {
                            main.post(() -> deepMarker(id, true, why));
                            return;
                        }
                        if (id.startsWith("save:")) {
                            if (id.endsWith(":last")) saveFinished();
                            return;
                        }
                        if (id.endsWith(":last")) allDone(Integer.parseInt(id.split(":")[0]));
                    }
                });
                // The second engine (it records God's, Jesus's and the characters' lines ahead) starts now, not at the first such
                // line: then the first is recorded ahead too, instead of by this engine only when its turn has come.
                if (Prefs.flag(ctx, "voice_fx", true) && !Prefs.testMode(ctx)) initSynth();
                Runnable r = whenReady;
                whenReady = null;
                if (r != null) r.run();
                for (Runnable c : pendingVoiceCallbacks) c.run();
                pendingVoiceCallbacks.clear();
            } else {
                Logs.d(ctx, "text-to-speech engine failed to start, status=" + status);
                try {
                    made[0].shutdown(); // let go of the voice app, or its connection stays open
                } catch (Exception ignored) {
                }
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
            translating = translate;
            if (translate) {
                translateNext(runs, gen, 0);
                return;
            }
            queuedTo = -1;
            queueAhead(runs, gen, 0);
        });
    }

    /** The last run handed to the voice; the rest follow as the voice gets to them. */
    private volatile int queuedTo = -1;
    private volatile boolean translating; // translateNext queues each run itself, as its translation comes
    private static final int AHEAD = 3;

    /**
     * Gives the voice only a few runs ahead of the one being spoken. Handing it a whole long page at once
     * (each run a call into the voice app, which waits while the voice app is busy) froze ZYLENA.
     */
    private void queueAhead(List<TextPrep.Seg> runs, int gen, int speaking) {
        // Also while God's or Jesus's words are being played: what is queued then waits its turn behind them (deepHeld), and
        // the next paragraphs' own lines (Jesus's teaching goes on) are recorded meanwhile, so no silence comes between them.
        if (gen != generation || runs != currentRuns || tts == null) return;
        // Gemini's voice gets more at a time: it asks for several paragraphs in one request.
        int upTo = Math.min(runs.size() - 1, speaking + (GeminiVoice.usable(ctx) ? 8 : AHEAD));
        while (queuedTo < upTo) {
            queuedTo++;
            queue(runs, gen, queuedTo);
        }
    }

    /** A paragraph's translation, asked for ahead of time. */
    private static final class Asked {
        String out, by = "";
        boolean done;
        long took, began = System.currentTimeMillis();
        Runnable waiter;
    }

    private final java.util.Map<Integer, Asked> asked = new java.util.HashMap<>();
    private int askedGen = -1;
    private long usualMs = 4000; // how long a translation has been taking, for the countdown
    private static final int AT_ONCE = 3;

    private boolean inTarget(TextPrep.Seg s) {
        String target = "en".equals(Prefs.translateTo(ctx)) ? TextPrep.EN : TextPrep.FIL;
        return s.translated || s.lang.equals(target);
    }

    /** The last run of the paragraph that starts at run i: a whole paragraph is translated in one go. */
    private int groupEnd(List<TextPrep.Seg> runs, int i) {
        TextPrep.Seg s = runs.get(i);
        int j = i, chars = s.text.length();
        while (j + 1 < runs.size()) {
            TextPrep.Seg n = runs.get(j + 1);
            if (n.translated || n.line != s.line || n.lastLine != s.lastLine || !n.lang.equals(s.lang) || n.heading != s.heading
                    || chars + n.text.length() > 2500) break;
            chars += n.text.length() + 1;
            j++;
        }
        return j;
    }

    /** Starts translating the paragraph at runs i..last, unless that was started already. Main thread. */
    private Asked ask(List<TextPrep.Seg> runs, int gen, int i, int last) {
        if (askedGen != gen) {
            asked.clear();
            askedGen = gen;
        }
        Asked a = asked.get(i);
        if (a != null) return a;
        final Asked p = new Asked();
        asked.put(i, p);
        TextPrep.Seg s = runs.get(i);
        StringBuilder all = new StringBuilder();
        for (int k = i; k <= last; k++) all.append(k > i ? " " : "").append(runs.get(k).text);
        // (The marks around Bible text are for the voice, not for a translator.)
        final String source = TextPrep.unmark(all.toString());
        // A quoted verse with its reference after it: the Tagalog Bible's own words, not a new translation.
        String ref = runs.get(last).ref;
        if (ref != null && s.lang.equals(TextPrep.EN)) {
            String verse = Bible.quotedVerse(source, ref);
            if (verse != null) {
                p.out = Translate.VERSE_ON + verse.trim().replaceAll("[\\s,;:]+$", "") + "." + Translate.VERSE_OFF;
                p.by = "the Tagalog Bible (" + ref + ")";
                p.done = true;
                Logs.d(ctx, "translated by " + p.by + ", lines " + s.line + "-" + runs.get(last).lastLine + ": " + Logs.snip(source));
                return p;
            }
        }
        String context = i > 0 ? runs.get(i - 1).text : "";
        final long began = System.currentTimeMillis();
        Translate.text(ctx, source, s.lang, context, out -> main.post(() -> {
            p.out = out;
            p.by = out == null ? "nobody" : Translate.lastBy;
            p.took = System.currentTimeMillis() - began;
            p.done = true;
            if (gen != generation) return;
            usualMs = (usualMs + p.took) / 2;
            Logs.d(ctx, "translated by " + p.by + " in " + p.took + " ms, lines " + s.line + "-" + runs.get(last).lastLine + ": "
                    + (source.length() > 700 ? source.substring(0, 700) + "…" : source));
            if (p.waiter != null) {
                Runnable w = p.waiter;
                p.waiter = null;
                w.run();
            }
        }));
        return p;
    }

    /** How many quoted parts these pieces of one paragraph hold together (one that runs on into the next piece is counted once). */
    static int quotedParts(List<String> pieces, boolean inQuote) {
        int n = 0;
        boolean in = inQuote;
        for (String t : pieces) {
            List<int[]> spans = Cast.quoteSpans(t, in);
            for (int[] q : spans) n += q[2];
            if (!spans.isEmpty()) in = spans.get(spans.size() - 1)[2] == 0;
        }
        return n + (in ? 1 : 0);
    }

    /** Puts a run of a translation in the voice's queue, behind any words that are still being made and played. */
    private void queueInTurn(List<TextPrep.Seg> runs, int gen, int i) {
        // While God's words (or a character's made voice) are being played, what queuePhone gives the voice waits its turn
        // behind them (it holds it itself), and the run's own styled lines are recorded meanwhile.
        queue(runs, gen, i);
    }

    /**
     * Queues run i in the asked-for language and goes on with the next. A few paragraphs are translated at
     * once, ahead of the voice, so it is only the very first one that is waited for. A paragraph that can't be
     * translated is read as it is.
     */
    private void translateNext(List<TextPrep.Seg> runs, int gen, int i) {
        if (gen != generation || i >= runs.size()) return;
        TextPrep.Seg s = runs.get(i);
        if (inTarget(s)) { // already in the language asked for
            queueInTurn(runs, gen, i);
            translateNext(runs, gen, i + 1);
            return;
        }
        final int last = groupEnd(runs, i);
        final Asked p = ask(runs, gen, i, last);
        // The next paragraphs too, at the same time.
        int n = last + 1;
        for (int more = 1; more < AT_ONCE && n < runs.size(); ) {
            if (inTarget(runs.get(n))) {
                n++;
                continue;
            }
            int e = groupEnd(runs, n);
            ask(runs, gen, n, e);
            n = e + 1;
            more++;
        }
        Runnable apply = () -> {
            if (gen != generation) return;
            String out = p.out;
            if (out != null && !out.trim().isEmpty()) {
                String to = Translate.other(s.lang);
                List<String> parts = TextPrep.shareOut(out, last - i + 1);
                // The characters' voices follow the quoted parts in order. The translation is cut into runs at other
                // places than the original was, so each run's place among the quoted parts is counted anew; and when
                // the translation has more or fewer quoted parts, who says which is not known any more.
                List<String> before = new ArrayList<>();
                for (int k = i; k <= last; k++) before.add(runs.get(k).text);
                final boolean lost = s.cast != null && quotedParts(before, s.inQuote) != quotedParts(parts, s.inQuote);
                int from = s.castFrom;
                boolean inQ = s.inQuote;
                for (int k = i; k <= last; k++) {
                    String part = parts.get(k - i);
                    TextPrep.Seg o = runs.get(k);
                    // Kept in the usual spelling (the log shows it so); respelled for the voice only when spoken.
                    TextPrep.Seg tr = new TextPrep.Seg(part, to, o.line, o.lastLine, o.heading, true).like(o);
                    if (o.cast != null) {
                        tr.castFrom = from;
                        tr.inQuote = inQ;
                        tr.castLost = lost;
                        List<int[]> spans = Cast.quoteSpans(part, inQ);
                        for (int[] q : spans) from += q[2];
                        if (!spans.isEmpty()) inQ = spans.get(spans.size() - 1)[2] == 0;
                    }
                    runs.set(k, tr);
                }
            } else if (i == 0) {
                toast("Couldn't translate. Reading as it is. The translation pack needs internet the first time.");
            }
            for (int k = i; k <= last; k++) queueInTurn(runs, gen, k);
            translateNext(runs, gen, last + 1);
        };
        if (p.done) {
            apply.run();
        } else {
            startWait(gen, p.began);
            p.waiter = () -> {
                endWait();
                apply.run();
            };
        }
    }

    private GeminiVoice gemini;
    private final List<int[]> handLate = new ArrayList<>(); // {gen, run}, main thread only

    private GeminiVoice gemini() {
        if (gemini == null) {
            gemini = new GeminiVoice(ctx, new GeminiVoice.Events() {
                @Override
                public void started(int gen, int run) {
                    runStarted(run, gen);
                }

                @Override
                public void finished(int gen) {
                    allDone(gen);
                }

                @Override
                public void toPhone(List<GeminiVoice.Item> items, String note) {
                    main.post(() -> {
                        try {
                            if (!items.isEmpty() && items.get(0).voice != null) { // a test of a voice: nothing else to read
                                toast("Gemini voice didn't answer this time. Try again in a few minutes.");
                                if (items.get(0).gen == generation) setSpeaking(false);
                                return;
                            }
                            if (note != null) toast(note);
                            List<TextPrep.Seg> runs = currentRuns;
                            for (GeminiVoice.Item it : items) {
                                if (it.gen == generation && it.run < runs.size()) queuePhone(runs, it.gen, it.run);
                            }
                            // Paragraphs that came meanwhile go behind them.
                            for (int[] late : handLate) {
                                if (late[0] == generation && late[1] < runs.size()) queuePhone(runs, late[0], late[1]);
                            }
                        } finally {
                            handLate.clear();
                            gemini.handing = false;
                        }
                    });
                }
            }, () -> generation);
        }
        return gemini;
    }

    /** Puts run i in the voice's queue: Gemini's voice when it is on and can, else the phone's voice. */
    private void queue(List<TextPrep.Seg> runs, int gen, int i) {
        if (gemini != null && gemini.handing) { // its paragraphs are going to the phone's voice: this one follows them
            handLate.add(new int[]{gen, i});
            return;
        }
        if (GeminiVoice.usable(ctx) && !Prefs.testMode(ctx)) {
            TextPrep.Seg s = runs.get(i);
            boolean newPara = i > 0 && runs.get(i - 1).lastLine != s.line;
            boolean head = newPara && (s.heading || runs.get(i - 1).heading);
            // Gemini reads the whole run itself, English verses and all, in its own natural way; the usual
            // spelling is kept (no respelling for the phone's voice), names and years said in full.
            String text = TextPrep.unmark(s.text);
            gemini().add(new GeminiVoice.Item(gen, i, TextPrep.sayName(text, s.lang), i == runs.size() - 1,
                    head ? HEADING_PAUSE_MS : newPara ? PARAGRAPH_PAUSE_MS : 0, rateFor(s.lang)));
            return;
        }
        queuePhone(runs, gen, i);
    }

    /** A run that starts with somebody speaking (a quotation), in a story. */
    /** Words after which a reader falls silent for a moment: a death, a last breath, an oath, Jesus weeping. */
    private static final java.util.regex.Pattern SOLEMN = java.util.regex.Pattern.compile("(?i)(?<![\\p{L}])(?:and\\s+(?:he|she)\\s+died|and\\s+died|gave\\s+up\\s+the\\s+ghost|yielded\\s+up\\s+the\\s+ghost|breathed\\s+his\\s+last|"
            + "jesus\\s+wept|it\\s+is\\s+finished|sware\\s+unto|made\\s+a\\s+vow|vowed\\s+a\\s+vow|was\\s+dead|is\\s+dead|at\\s+namatay|ay\\s+namatay|nalagot\\s+ang\\s+(?:kaniyang\\s+)?hininga|"
            + "tumangis\\s+si\\s+jesus|naganap\\s+na|sumumpa|nanata|she\\s+died|he\\s+died|namatay\\s+siya|patay\\s+na)(?![\\p{L}])[^.!?]*[.!?\u201D\"']*\\s*$");

    private static boolean quickTalk(TextPrep.Seg r) {
        if (r.cast == null) return false;
        String t = r.text.trim();
        return !t.isEmpty() && "\"\u201C\u00AB".indexOf(t.charAt(0)) >= 0;
    }

    /** Puts run i in the phone voice's queue, with a breath before it when a new paragraph starts. */
    private void queuePhone(List<TextPrep.Seg> runs, int gen, int i) {
        TextPrep.Seg s = runs.get(i);
        applyVoice(s.lang);
        Voice used = chosenVoice(s.lang);
        if (used != null && used.isNetworkConnectionRequired()) Net.spokeOnline(ctx, s.text.length());
        if (i == 0 || !runs.get(i - 1).lang.equals(s.lang)) {
            Logs.d(ctx, "voice for " + s.lang + ": " + (used == null ? "(default locale)" : used.getName()));
        }
        boolean last = i == runs.size() - 1;
        // A less machine-like rhythm: each sentence a little different (the same each time it is read), and uneven pauses.
        final boolean natural = Prefs.flag(ctx, "natural", true) && !Prefs.testMode(ctx);
        final int nh = ((i + 1) * 0x9E3779B1) >>> 16;
        final float natR = natural ? 1f + ((nh % 7) - 3) * 0.01f : 1f, natP = natural ? 1f + (((nh / 7) % 5) - 2) * 0.01f : 1f;
        final List<Long> leadIn = new ArrayList<>(); // the silences before this run, in order
        if (i > 0 && runs.get(i - 1).lastLine != s.line) {
            // A short breath between paragraphs, a little longer around a heading. The online voice already
            // waits a moment before each piece, so it gets no extra breath between paragraphs.
            boolean head = s.heading || runs.get(i - 1).heading;
            boolean online = used != null && used.isNetworkConnectionRequired();
            // The pauses are the user's (Voice > Pauses); the online voice's own wait before each piece counts toward them.
            long want = (long) ((head ? Prefs.pauseHeading(ctx) : Prefs.pauseParagraph(ctx)) * 1000);
            long breath = online ? Math.max(0, want - 300) : want;
            // People answering each other quickly: a shorter gap than between narration paragraphs.
            if (quickTalk(runs.get(i - 1)) && quickTalk(s)) breath = breath * 55 / 100;
            // Not the same length every time; longer after a long paragraph, a question or an exclamation.
            if (natural && breath > 0) {
                String before = runs.get(i - 1).text.trim();
                float f = 0.8f + (nh % 9) * 0.05f;
                if (before.length() > 220) f += 0.15f;
                if (before.endsWith("?") || before.endsWith("!")) f += 0.1f;
                breath = (long) (breath * f);
            }
            if (breath > 0) leadIn.add(breath);
        }
        // After a death, a vow, "Jesus wept", "It is finished": a moment of silence before the story goes on.
        if (i > 0 && !Prefs.testMode(ctx) && SOLEMN.matcher(runs.get(i - 1).text).find()) leadIn.add(850L);
        // A scene change: a longer pause first, the way a narrator would take a breath before it.
        if (s.scene && i > 0) leadIn.add(900L);
        // The Father, the Son or the Holy Spirit begins to speak after the story: a moment of stillness first.
        else if (i > 0 && !Prefs.testMode(ctx) && quickTalk(s) && s.cast.length > 0 && s.cast[0] != null && Cast.isDivine(Cast.voice(s.cast[0]))
                && !quickTalk(runs.get(i - 1))) leadIn.add(380L);
        // (Given to the voice in their turn: behind God's or Jesus's words when those are still being played.)
        if (!leadIn.isEmpty()) {
            Runnable lead = () -> {
                if (gen != generation || tts == null) return;
                for (long ms : leadIn) tts.playSilentUtterance(ms, TextToSpeech.QUEUE_ADD, gen + ":-1");
            };
            if (deepHold) deepHeld.add(lead);
            else lead.run();
        }
        // Voice and language switch between runs, so each run is queued as its own utterance; a Bible verse kept
        // in English inside a Tagalog run is its own utterance too, in the English voice.
        // A question is a piece of its own as well, said a little higher and slower, with a moment after it.
        List<String[]> parts = new ArrayList<>(); // {text, language, tone}
        boolean tone = Prefs.questionTone(ctx);
        for (String[] vp : voiceParts(s)) {
            String said = TextPrep.say(vp[0], vp[1]);
            // Jesus's words are said exactly as "Hear every voice" plays him (the owner's choice): no question or exclamation tones.
            if (!tone || Cast.JESUS_CODE.equals(Cast.voice(vp[2]))) parts.add(new String[]{said, vp[1], "", vp[2]});
            else for (String[] t : TextPrep.tonePieces(said)) parts.add(new String[]{t[0], vp[1], t[1], vp[2]});
        }
        // A stutter ("L-lalabas", "I-I'm") is said in pieces with a stop between them: each repeated start, then the word.
        // The first stop is the longest and the next ones shorter, as in real speech; the last piece keeps the tone.
        for (int k = 0; k < parts.size(); k++) {
            String[] pt = parts.get(k);
            if (pt[0].indexOf(TextPrep.HALT) < 0) continue;
            String[] bits = pt[0].split(String.valueOf(TextPrep.HALT), -1);
            parts.remove(k);
            int at = k;
            for (int b = 0; b < bits.length; b++) {
                String bit = bits[b].trim();
                if (bit.isEmpty()) continue;
                boolean end = b == bits.length - 1;
                parts.add(at++, new String[]{bit, pt[1], end ? pt[2] : "", pt[3], end ? "" : (b == 0 ? "340" : "260")});
            }
            if (at == k) parts.add(at++, new String[]{"", pt[1], pt[2], pt[3]});
            k = at - 1;
        }
        // The sound effect of a sentence like "...," she answered with a laugh: it plays at the narration, after the character's words.
        if (!s.sfx.isEmpty()) {
            int at = 0;
            if (!parts.isEmpty() && Cast.isCode(Cast.voice(parts.get(0)[3]))) {
                for (int k = 1; k < parts.size(); k++) {
                    if (!Cast.isCode(Cast.voice(parts.get(k)[3]))) {
                        at = k;
                        break;
                    }
                }
            }
            sfxAt.put(i, at);
        }
        // "Announce who is speaking" (Settings, off unless chosen): the storyteller says the name before a character's words,
        // each time somebody else starts to speak.
        if (Prefs.flag(ctx, "announce_speaker", false)) {
            if (announcedGen != gen) {
                announcedGen = gen;
                announced = null;
            }
            String[] lastNamed = {announced};
            List<String> named = new ArrayList<>();
            int first = nameSpeakers(parts, lastNamed, s.who, named);
            announced = lastNamed[0];
            if (first >= 0) {
                Integer at = sfxAt.get(i);
                if (at != null && at >= first) sfxAt.put(i, at + named.size());
            }
            if (!named.isEmpty()) Logs.d(ctx, "announced in run " + i + ": " + String.join(", ", named));
        }
        // Reading waits while an effect plays, so the sound and the voice are each heard clearly.
        long sfxHold = 0;
        final String sfxId = gen + ":" + i + ":x";
        if (!s.sfx.isEmpty()) {
            String base = s.sfx.indexOf('@') < 0 ? s.sfx : s.sfx.substring(0, s.sfx.indexOf('@'));
            Integer lastRun = sfxLastRun.get(base);
            int gap = Sfx.person(base) ? 8 : 5; // the same sound again and again is tiresome
            if (lastRun == null || Math.abs(i - lastRun) >= gap) {
                sfxHold = Sfx.holdMs(ctx, s.sfx);
                // Every sound, the long ones too (a trumpet, the sea, a crowd): the reading pauses, the sound plays alone, then the reading goes on.
                if (sfxHold > 0) {
                    sfxLastRun.put(base, i);
                    sfxNow.put(sfxId, s.sfx);
                }
            }
        }
        final long sfxHoldF = sfxHold;
        final int sfxPart = sfxAt.get(i) == null ? 0 : sfxAt.get(i);
        if (s.cast != null && parts.size() > 1) {
            StringBuilder v = new StringBuilder();
            for (String[] pt : parts) if (v.indexOf(pt[3].isEmpty() ? "usual" : pt[3]) < 0) v.append(v.length() > 0 ? ", " : "").append(pt[3].isEmpty() ? "usual" : pt[3]);
            if (v.indexOf(",") > 0 && !castLoggedFor.contains(s.lang)) {
                castLogged = true;
                castLoggedFor.add(s.lang);
                Logs.d(ctx, "story voices for " + s.lang + ": " + castVoices(s.lang));
            }
            if (v.indexOf(",") > 0) Logs.d(ctx, "voices in run " + i + ": " + v);
        }
        long change = (long) (Prefs.num(ctx, "pause_voice", 0f) * 1000);
        float pitch = Prefs.pitch(ctx), strength = Prefs.toneStrength(ctx);
        // A heading is announced, and a paragraph that is one quotation (a memory verse) is read with care:
        // both a little lower and slower. They are whole runs already, so no extra pause comes of it.
        String whole = !tone ? "" : s.heading ? "h" : TextPrep.wholeQuote(s.text) ? "v" : "";
        final boolean dsp = !Prefs.testMode(ctx) && Prefs.flag(ctx, "voice_fx", true);
        float partsTotal = 1f, partsDone = 0f;
        {
            int tot = 0;
            for (String[] pt : parts) tot += pt[0].length();
            partsTotal = Math.max(1, tot);
        }
        for (int k = 0; k < parts.size(); k++) {
            String lang = parts.get(k)[1];
            // Jesus is said exactly as "Hear every voice" plays him: his voice at the reading's speed and pitch, and nothing else on
            // top (no feeling, no tone, no kind of text, no ups and downs of the natural rhythm). Those made him sound wrong.
            final boolean asHeard = Cast.JESUS_CODE.equals(Cast.voice(parts.get(k)[3]));
            String t = asHeard ? "" : parts.get(k)[2].isEmpty() ? whole : parts.get(k)[2];
            boolean ask = t.equals("q");
            float p = ask ? QUESTION_PITCH : t.equals("e") ? 1.06f : t.equals("h") ? 0.97f : t.equals("v") ? 0.96f : t.equals("t") ? 0.97f : 1f;
            boolean divineLine = Cast.voice(parts.get(k)[3]) != null && Cast.voice(parts.get(k)[3]).startsWith("cd");
            float r = ask ? 0.96f : t.equals("e") ? 1.02f : t.equals("h") ? 0.93f : t.equals("v") ? 0.94f : t.equals("t") ? 0.9f : 1f;
            // How strong the tones are is the user's choice (Voice > Tone strength).
            p = 1f + (p - 1f) * strength;
            r = 1f + (r - 1f) * strength;
            if (natural && !asHeard) {
                p *= natP;
                r *= natR;
                // The storyteller (not a character) tells the story a touch slower.
                if (s.story && !Cast.isCode(Cast.voice(parts.get(k)[3]))) r *= 0.97f;
            }
            // A character with a voice of their own kind (God's, a child's, an old person's, a husky one, a boss's, a maid's)
            // is made by processing the sound; see VoiceFx.
            // How the line is said (shouted, angry, sad, afraid, laughing, whispered…) is made audible the same way: an online voice
            // often ignores a change of pitch, and a few percent of speed or volume is not heard.
            final String fcode = asHeard ? "" : Cast.feel(parts.get(k)[3]);
            String effCode = fcode;
            float effK = strength;
            if (fcode.isEmpty() && !s.narrFeel.isEmpty() && !Cast.isCode(Cast.voice(parts.get(k)[3]))) {
                effCode = s.narrFeel; // the storyteller's own words carry a feeling too, less strongly
                effK = strength * 0.7f;
            }
            final VoiceFx.Style feelFx = dsp && !effCode.isEmpty() ? VoiceFx.forFeeling(effCode) : null;
            final VoiceFx.Style fx0 = dsp ? VoiceFx.combine(styleFor(lang, parts.get(k)[3]), feelFx, effK) : null;
            // A man's voice for Bible text and a man's point of view; with none on the phone, the usual voice lower.
            float vm = applyVoice(lang, parts.get(k)[3]);
            // For the log: who reads each part and in which phone voice, whenever that changes within the run.
            if (k == 0 || !parts.get(k)[3].equals(parts.get(k - 1)[3])) {
                String w3 = parts.get(k)[3], wv = Cast.voice(w3);
                String label = w3.isEmpty() || w3.startsWith("~") ? "storyteller" : "m".equals(wv) ? "Bible voice"
                        : Cast.isCode(wv) ? (Cast.nameOf(wv, lang.equals(TextPrep.FIL)) == null ? wv : Cast.nameOf(wv, lang.equals(TextPrep.FIL)) + " (" + wv + ")") : w3;
                String said = parts.get(k)[0];
                Logs.d(ctx, "run " + i + " part " + k + ": " + label + " in " + (lastSet == null ? "the default voice" : base(lastSet.getName()))
                        + (vm != 1f ? " (lowered " + vm + ")" : "") + ": " + (said.length() > 50 ? said.substring(0, 50) + "\u2026" : said));
            }
            // No man's voice in this language for God's words (Tagalog on many phones): asking the voice to speak lower is
            // ignored by online voices, so the sound itself is lowered that much more, and it comes out a man's.
            final float dd = divineLine ? depthFor(Cast.voice(parts.get(k)[3])) : 1f;
            final boolean noMan = fx0 != null && divineLine && vm / dd < 0.97f;
            final VoiceFx.Style fx = noMan ? VoiceFx.lower(fx0, 0.82f) : fx0;
            if (noMan && !noManLogged.contains(lang)) {
                noManLogged.add(lang);
                Logs.d(ctx, "voice fx: no man's voice for " + lang + ", God's words are made from the usual voice, lowered more");
            }
            if (fx != null) r *= fx.tf / fx.pf; // said at this speed, it comes out at the tempo asked once the pitch is changed
            else if (divineLine && !plainJesus(Cast.voice(parts.get(k)[3]))) r *= 0.92f; // slower, with weight
            if (fx != null) p *= divineLine ? (noMan ? 1f : vm / dd) : vm; // the pitch is changed afterwards
            else p *= vm;
            // The kind of text (suspense lower and slower, comedy livelier) and how the line is said (whispered, shouted).
            float[] kind = asHeard ? new float[]{1f, 1f, 1f} : Mood.forKind(s.mood),
                    feel = feelFx != null || asHeard ? new float[]{1f, 1f, 1f} : Mood.forFeeling(Cast.feel(parts.get(k)[3]));
            // The storyteller's own words ("my heart pounded", "tears streamed down") carry a feeling too, at half strength.
            if (feelFx == null && Cast.feel(parts.get(k)[3]).isEmpty() && !s.narrFeel.isEmpty() && !Cast.isCode(Cast.voice(parts.get(k)[3]))) {
                float[] nf = Mood.forFeeling(s.narrFeel);
                feel = new float[]{1f + (nf[0] - 1f) * 0.5f, 1f + (nf[1] - 1f) * 0.5f, 1f + (nf[2] - 1f) * 0.5f};
            }
            p *= (1f + (kind[0] - 1f) * strength) * (1f + (feel[0] - 1f) * strength);
            r *= (1f + (kind[1] - 1f) * strength) * (1f + (feel[1] - 1f) * strength);
            // A story is read a little under full loudness, so that a shout can be louder than the rest.
            float base = s.story && TextPrep.feelOn ? 0.8f : 1f;
            float loud = Math.max(0.3f, Math.min(1f, base * (1f + (kind[2] - 1f) * strength) * (1f + (feel[2] - 1f) * strength)));
            if (divineLine) loud = Math.max(loud, 0.9f); // God's words are never quiet
            if (asHeard) loud = 1f; // (as "Hear every voice" plays him)
            android.os.Bundle how = null;
            if (loud < 0.99f) {
                how = new android.os.Bundle();
                how.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, loud);
            }
            final String say = parts.get(k)[0], who = parts.get(k)[3];
            final boolean lastPart = last && k == parts.size() - 1;
            final String id = gen + ":" + i + (parts.size() > 1 ? ":p" + k : "") + (lastPart ? ":last" : "");
            int plen = say.length();
            partSpan.put(id, new float[]{partsDone / partsTotal, plen / partsTotal, plen});
            partsDone += plen;
            final float fr = rateFor(lang) * r, fp = pitch * p, fl = loud;
            final android.os.Bundle fhow = how;
            // A breath when another voice takes over, if the user wants one.
            final boolean breath = change > 0 && k > 0 && !Cast.voice(who).equals(Cast.voice(parts.get(k - 1)[3]));
            // Time to think about the question, unless the page ends here.
            final long after = (long) (Prefs.pauseQuestion(ctx) * 1000);
            final boolean think = ask && after > 0 && !lastPart;
            // A short beat, a trailing off, a cut-off: a pause after it, when more of the paragraph follows.
            final long halt = parts.get(k).length > 4 && !parts.get(k)[4].isEmpty() ? Long.parseLong(parts.get(k)[4]) : 0;
            final long gapAfter = (k < parts.size() - 1 ? (long) ((t.equals("b") ? 240 : t.equals("t") ? 380 : t.equals("c") ? 300 : 0) * strength) : 0) + halt;
            final int run = i, part = k;
            final String fl2 = lang;
            // God's, Jesus's or a character's own voice: recorded now, in pieces, by the second engine (see prepareDeep).
            final DeepReq deep = fx != null ? prepareDeep(say, id, fr, fp, fl * fx.vol, run, part, lastPart, gen, fx) : null;
            Runnable emit = () -> {
                if (gen != generation || tts == null) return;
                if (breath) tts.playSilentUtterance(change, TextToSpeech.QUEUE_ADD, gen + ":-1");
                if (sfxHoldF > 0 && part == sfxPart) tts.playSilentUtterance(sfxHoldF, TextToSpeech.QUEUE_ADD, sfxId);
                applyVoice(fl2, who);
                tts.setSpeechRate(fr);
                tts.setPitch(fp);
                if (deep != null) deepTurn(deep);
                else tts.speak(say, TextToSpeech.QUEUE_ADD, fhow, id);
                if (think) tts.playSilentUtterance(after, TextToSpeech.QUEUE_ADD, gen + ":-1");
                if (gapAfter > 0) tts.playSilentUtterance(gapAfter, TextToSpeech.QUEUE_ADD, gen + ":-1");
            };
            // What comes after God's words waits until they have been played (they are played by ZYLENA itself).
            if (deepHold) deepHeld.add(emit);
            else emit.run();
        }
        tts.setPitch(pitch);
    }

    // ------------------------------------------------------------ God's voice

    /**
     * How the voice of God, Jesus and the Holy Spirit is made. Asking the engine for a lower pitch is not enough:
     * Google's online voices ignore it, so the words would sound like anyone's. So these words are first
     * recorded to a file by the voice, then made into the Father's, the Son's or the Spirit's voice (each has a pitch,
     * a colour and a room of its own; see VoiceFx) and played by ZYLENA, whatever voice the phone uses.
     * What follows waits until they have been played.
     */
    private static final float DEEP = 0.78f;

    /**
     * How much lower the voice itself is asked to speak when the sound is not processed (voice effects off, or the recording
     * failed): the Father deep, the Son, a man among men, only a little lower than the plain voice, the Holy Spirit hardly at all, so that the three
     * are still not one voice.
     */
    private static float divineDepth(String code) {
        return Cast.JESUS_CODE.equals(code) ? 0.93f : Cast.SPIRIT_CODE.equals(code) ? 0.97f : DEEP;
    }

    /** Whose voice these words are said in: Jesus can be given God the Father's (Voice > Bible voices > Jesus's voice). */
    private String divineAs(String code) {
        return Cast.JESUS_CODE.equals(code) && "father".equals(Prefs.jesusVoice(ctx)) ? Cast.DIVINE_CODE : code;
    }

    /** Jesus is read in the phone voice as it is: nothing lowered, slowed or changed. */
    private boolean plainJesus(String code) {
        return Cast.JESUS_CODE.equals(code) && "plain".equals(Prefs.jesusVoice(ctx));
    }

    /** How much lower a divine speaker's voice is asked to be (1: as it is). */
    private float depthFor(String code) {
        return plainJesus(code) ? 1f : divineDepth(divineAs(code));
    }
    private volatile boolean deepHold;
    private final List<Runnable> deepHeld = new ArrayList<>();
    /** What is being played of God's, Jesus's or a character's words: two at a time while one piece fades into the next. */
    private final List<android.media.AudioTrack> deepTracks = new ArrayList<>();
    private Runnable deepFinishTask;

    private static final String[] MEN_VARIANTS = {"", "bass", "teen", "gruff"}, WOMEN_VARIANTS = {"", "gentle", "teen", "cute"};

    /**
     * The kind of voice a character has, from the voice code: God's, or what the story says they are like; null for the plain
     * voice. When the phone has only one voice for a man (or a woman) in this language, characters are told apart by how
     * the sound is changed, so that no two sound the same.
     */
    /** The name of the kind of voice the Father, the Son or the Holy Spirit has in this language. */
    static String divineStyle(String lang, String v) {
        String base = Cast.JESUS_CODE.equals(v) ? "jesus" : Cast.SPIRIT_CODE.equals(v) ? "spirit" : "deep";
        return base + (lang.equals(TextPrep.FIL) ? "_fil" : "");
    }

    private VoiceFx.Style styleFor(String lang, String who) {
        String v = Cast.voice(who);
        if (v == null || !Cast.isCode(v)) return null;
        if (v.startsWith("cd")) {
            // The Father, the Son, the Holy Spirit: each their own; gentler in Tagalog (only one man's voice there).
            if (plainJesus(v)) return null; // Jesus in the phone voice as it is (Voice > Bible voices)
            if (VoiceFx.removed(divineStyle(lang, v))) return null; // removed by the owner: the plain voice
            return VoiceFx.use(divineStyle(lang, divineAs(v)));
        }
        String st = Persona.kindOf(Cast.styleOf(who));
        if (st.equals("plain")) return null;
        if (!st.isEmpty()) return VoiceFx.use(st);
        char g = Cast.genderOf(v);
        if (g != 'm' && g != 'f') return null;
        if (castPool(lang, g).size() >= 2) return null;
        String[] variants = g == 'm' ? MEN_VARIANTS : WOMEN_VARIANTS;
        return VoiceFx.use(variants[Cast.slotOf(v) % variants.length]);
    }

    /** One line to be made into a character's voice: recorded by a second voice engine while the line before it is spoken. */
    private static final class DeepReq {
        java.io.File f;
        float loud, rate, pitch;
        int run, part, gen;
        boolean last, pre, recorded, failed, reached, working, first;
        String text, id, rid, voice = "", why = "";
        Voice voiceObj;
        VoiceFx.Style fx;
        /** The next piece of the same line (a long one is recorded and played in pieces), or null. */
        DeepReq next;
        /** Where its last word ends in pcm (the room rings on after it), and the mark it ends with (a full stop, a comma…). */
        int speechEnd;
        char endMark;
        // The sound, changed into the voice wanted (on a thread of its own, as soon as it is recorded), and its levels.
        short[] pcm;
        int sr;
        float[] stats;
    }

    /** Changes recordings into the voice wanted, one at a time, away from the main thread (a long one took seconds there). */
    private final java.util.concurrent.ExecutorService fxWork = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zylena-voicefx");
        t.setDaemon(true);
        return t;
    });

    /** Why the voice app could not do it, in words, for the log. */
    static String ttsError(int code) {
        switch (code) {
            case TextToSpeech.ERROR_NETWORK:
                return "no connection to the online voice";
            case TextToSpeech.ERROR_NETWORK_TIMEOUT:
                return "the online voice took too long";
            case TextToSpeech.ERROR_SYNTHESIS:
                return "the voice could not say it";
            case TextToSpeech.ERROR_INVALID_REQUEST:
                return "the voice refused the text";
            case TextToSpeech.ERROR_OUTPUT:
                return "the sound could not be written";
            case TextToSpeech.ERROR_SERVICE:
                return "the voice app stopped";
            case TextToSpeech.ERROR_NOT_INSTALLED_YET:
                return "the voice is not downloaded yet";
            default:
                return "error " + code;
        }
    }

    private final java.util.Map<String, DeepReq> deepReqs = new java.util.concurrent.ConcurrentHashMap<>();
    private TextToSpeech ttsSynth;
    private volatile boolean synthReady;

    /**
     * A second voice engine that only records (to files), so that the next styled line is already made when its turn
     * comes, with no wait while the first engine is busy speaking.
     */
    private void initSynth() {
        if (ttsSynth != null) return;
        String pkg = Prefs.ttsPackage(ctx);
        final TextToSpeech[] made = new TextToSpeech[1];
        made[0] = ttsSynth = new TextToSpeech(ctx, status -> main.post(() -> {
            if (status != TextToSpeech.SUCCESS || ttsSynth != made[0]) {
                // Failed, or another voice app was chosen meanwhile: this one is let go.
                if (ttsSynth == made[0]) ttsSynth = null;
                try {
                    made[0].shutdown();
                } catch (Exception ignored) {
                }
                return;
            }
            ttsSynth.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    main.post(() -> deepRecorded(id, false, ""));
                }

                @Override
                public void onError(String id, int code) {
                    main.post(() -> deepRecorded(id, true, ttsError(code)));
                }

                @Override
                public void onError(String id) {
                    main.post(() -> deepRecorded(id, true, "the voice could not record it"));
                }
            });
            synthReady = true;
            Logs.d(ctx, "voice fx: a second voice engine records the next lines ahead");
        }), pkg.isEmpty() ? null : pkg);
    }

    /** Letters in the first piece of a long line (made quickly, so it is ready in time) and in each piece after it. */
    private static final int FIRST_PIECE = 220, PIECE = 600;

    /**
     * A long line cut at its sentences into pieces: the first one short, so it is recorded in a moment, the next ones longer
     * (each is recorded while the one before it plays). A short line is one piece.
     */
    static List<String> deepPieces(String text) {
        List<String> out = new ArrayList<>();
        String t = text.trim();
        while (true) {
            int max = out.isEmpty() ? FIRST_PIECE : PIECE;
            if (t.length() <= max * 13 / 10) break;
            int cut = cutNear(t, max);
            if (cut <= 0) break;
            out.add(t.substring(0, cut).trim());
            t = t.substring(cut).trim();
        }
        if (!t.isEmpty()) out.add(t);
        return out;
    }

    /** Where to cut near max letters: after the end of a sentence, else after ; or :, else after a comma; -1 if there is none. */
    private static int cutNear(String t, int max) {
        int lo = max / 2, hi = Math.min(t.length() - 1, max * 13 / 10);
        for (String marks : new String[]{".!?", ";:", ","}) {
            int best = -1;
            for (int i = lo; i <= hi; i++) {
                if (marks.indexOf(t.charAt(i)) < 0) continue;
                int j = i + 1;
                while (j < t.length() && "\"'\u201D\u2019\u00BB)]".indexOf(t.charAt(j)) >= 0) j++;
                if (j >= t.length() || !Character.isWhitespace(t.charAt(j))) continue; // "5:17", "Mr.Smith": not a stop
                if (best < 0 || Math.abs(j - max) < Math.abs(best - max)) best = j;
            }
            if (best > 0) return best;
        }
        return -1;
    }

    /**
     * A line in a voice of its own (God's, Jesus's, a character's) is recorded when it is queued, not when its turn comes: cut at
     * its sentences into pieces, each recorded by the second engine in order, so the first is ready by the time the voice gets
     * there and each next one while the one before it plays. (Jesus's long teaching in one piece took 20 seconds to record,
     * and the reading stood silent until it was all made.) Without the second engine, each piece is recorded by the first
     * engine when its turn comes.
     */
    private DeepReq prepareDeep(String text, String id, float rate, float pitch, float loud, int run, int part, boolean last, int gen, VoiceFx.Style fx) {
        if (!synthReady) initSynth();
        Voice v = lastSet; // the voice applyVoice has just given this line
        if (v == null) {
            try {
                v = tts.getVoice();
            } catch (Exception ignored) {
            }
        }
        List<String> pieces = deepPieces(text);
        DeepReq first = null, prev = null;
        for (int c = 0; c < pieces.size(); c++) {
            DeepReq d = new DeepReq();
            d.rid = "deep:" + id + "#" + c;
            d.f = new java.io.File(ctx.getCacheDir(), "deep-" + (id + "_" + c).replace(':', '_') + ".wav");
            d.loud = loud;
            d.rate = rate;
            d.pitch = pitch;
            d.run = run;
            d.part = part;
            d.gen = gen;
            d.first = c == 0;
            d.last = last && c == pieces.size() - 1;
            d.text = pieces.get(c);
            d.endMark = endMark(d.text);
            d.id = id; // the line's own: when it is read the ordinary way, its end is the line's end
            d.fx = fx;
            d.voiceObj = v;
            if (v != null) d.voice = v.getName();
            deepReqs.put(d.rid, d);
            if (prev != null) prev.next = d;
            else first = d;
            prev = d;
            if (synthReady && ttsSynth != null && v != null) {
                d.pre = true;
                ttsSynth.setVoice(v);
                ttsSynth.setSpeechRate(rate);
                ttsSynth.setPitch(pitch);
                if (ttsSynth.synthesizeToFile(d.text, null, d.f, d.rid) != TextToSpeech.SUCCESS) fail(d, "the second voice engine refused it");
            }
        }
        if (pieces.size() > 1) Logs.d(ctx, "voice fx: a long line (" + text.length() + " letters) is recorded in " + pieces.size() + " pieces");
        return first;
    }

    /** The mark a piece ends with (quotation marks aside): it says how long a speaker would pause after it. */
    private static char endMark(String t) {
        for (int i = t.length() - 1; i >= 0; i--) {
            char ch = t.charAt(i);
            if (Character.isWhitespace(ch) || "\"'\u201D\u2019\u00BB)]".indexOf(ch) >= 0) continue;
            return ch;
        }
        return '.';
    }

    /** How long a speaker pauses after this mark before going on (after a full stop longer than after a comma), at the reading speed. */
    private long pauseAfter(char mark) {
        long ms = ".!?".indexOf(mark) >= 0 ? 400 : ";:".indexOf(mark) >= 0 ? 260 : mark == ',' ? 160 : 220;
        return (long) (ms / Math.max(0.5f, Prefs.rate(ctx)));
    }

    /** The line's turn has come in the voice's queue: what follows waits until all its pieces have been played. */
    private void deepTurn(DeepReq d) {
        deepHold = true;
        if (d.pre) {
            // Recorded (or being recorded) by the second engine: this marks its place among what the first engine says.
            tts.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, d.rid);
        } else {
            recordNow(d);
        }
    }

    /** No second engine: the first engine records the piece now (reading waits meanwhile), then it is played. */
    private void recordNow(DeepReq d) {
        if (d.voiceObj != null) {
            tts.setVoice(d.voiceObj);
            lastSet = d.voiceObj;
        }
        tts.setSpeechRate(d.rate);
        tts.setPitch(d.pitch);
        if (tts.synthesizeToFile(d.text, null, d.f, d.rid) != TextToSpeech.SUCCESS) {
            fail(d, "the voice refused to record it");
            d.reached = true;
            final String rid = d.rid;
            main.post(() -> deepTry(rid));
        }
    }

    /** A piece has been played: the next piece of the same line plays at once if it is ready, else as soon as it is. */
    private void nextPiece(DeepReq d) {
        if (d.gen != generation || tts == null) return;
        if (!d.pre) {
            recordNow(d);
            return;
        }
        d.reached = true;
        watch(d);
        deepTry(d.rid);
    }

    /** Its turn has come and the second engine has not finished it: after a while it is read the ordinary way instead. */
    private void watch(DeepReq d) {
        final String rid = d.rid;
        main.postDelayed(() -> {
            DeepReq x = deepReqs.get(rid);
            if (x == null || x.recorded) return;
            fail(x, "the second voice engine took too long");
            deepTry(rid);
        }, 15000 + d.text.length() * 60L);
    }

    /** The first engine began recording a line itself (no second engine): from now it may take only so long. */
    private void deepStarted(String rid) {
        DeepReq d = deepReqs.get(rid);
        if (d == null || d.pre) return;
        main.postDelayed(() -> {
            DeepReq x = deepReqs.get(rid);
            if (x == null) return;
            if (!x.recorded) fail(x, "the voice took too long to record it");
            x.reached = true;
            deepTry(rid);
        }, 10000 + d.text.length() * 90L);
    }

    /** The recording of a line is done (or failed), by the second engine (pre) or by the first one. */
    private void deepRecorded(String rid, boolean error, String why) {
        DeepReq d = deepReqs.get(rid);
        if (d == null) return;
        if (error) {
            fail(d, why);
        } else {
            d.recorded = true;
            change(rid, d);
        }
        deepTry(rid);
    }

    /** The first engine got to this line's place in its queue (after everything before it was said). */
    private void deepMarker(String rid, boolean error, String why) {
        DeepReq d = deepReqs.get(rid);
        if (d == null) return;
        if (d.pre && !d.reached && !d.recorded) watch(d);
        d.reached = true;
        if (!d.pre) { // the first engine made the recording itself: this is its end
            if (error) {
                fail(d, why);
            } else {
                d.recorded = true;
                change(rid, d);
            }
        }
        deepTry(rid);
    }

    /** The first reason a line could not be made is the one kept (for the log). */
    private static void fail(DeepReq d, String why) {
        if (d.failed) return;
        d.failed = true;
        d.why = why == null ? "" : why;
    }

    /**
     * The recording is changed into the voice wanted as soon as it is there, on a thread of its own: by the time its turn
     * comes it is ready, so the voice starts at once (changing a long one on the main thread held everything up for seconds).
     */
    private void change(String rid, DeepReq d) {
        if (d.working || d.pcm != null || d.failed) return;
        d.working = true;
        fxWork.execute(() -> {
            short[] out = null;
            int sr = 0;
            String err = null;
            float[] stats = new float[7];
            int end = 0;
            try {
                byte[] b = new byte[(int) d.f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(d.f)) {
                    int off = 0, n;
                    while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                }
                int[] w = wavInfo(b);
                if (w == null || w[3] < 400) throw new java.io.IOException("not a usable recording");
                sr = w[0];
                short[] pcm = new short[w[3] / 2];
                java.nio.ByteBuffer.wrap(b, w[2], w[3]).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                if (w[1] == 2) { // mono
                    short[] mono = new short[pcm.length / 2];
                    for (int i = 0; i < mono.length; i++) mono[i] = (short) ((pcm[2 * i] + pcm[2 * i + 1]) / 2);
                    pcm = mono;
                }
                out = VoiceFx.process(pcm, sr, d.fx, d.fx.level, d.fx.presence, stats);
                // The silence the voice puts before the first word is taken off (all but a breath), so the voice comes in at once.
                int from = Math.max(0, (int) stats[5] - sr * 30 / 1000);
                end = stats[6] > stats[5] ? (int) stats[6] - from : out.length - from;
                if (from > 0 && from < out.length) out = java.util.Arrays.copyOfRange(out, from, out.length);
            } catch (Throwable e) { // out of memory too
                err = String.valueOf(e);
                out = null;
            }
            d.f.delete();
            final short[] o = out;
            final int rate = sr;
            final String er = err;
            final int spokenTo = end;
            main.post(() -> {
                d.working = false;
                if (o != null) {
                    d.pcm = o;
                    d.sr = rate;
                    d.stats = stats;
                    d.speechEnd = spokenTo > 0 && spokenTo <= o.length ? spokenTo : o.length;
                } else {
                    fail(d, "the sound could not be changed (" + er + ")");
                }
                deepTry(rid);
            });
        });
    }

    /** Plays the line when its turn has come and it is ready; reads it the ordinary way when it could not be made. */
    private void deepTry(String rid) {
        DeepReq d = deepReqs.get(rid);
        if (d == null || !d.reached) return;
        if (!d.failed && d.pcm == null) return; // still being recorded or changed: it plays as soon as it is ready
        deepReqs.remove(rid);
        if (d.gen != generation) {
            d.f.delete();
            return;
        }
        if (d.failed) {
            d.f.delete();
            deepPlainly(d);
        } else {
            playDeep(d);
        }
    }

    /**
     * The voice could not make the file: the words are read the ordinary way, lower, in the same phone voice, and reading
     * goes on. The pieces of the line still to come are read with it.
     */
    private void deepPlainly(DeepReq d) {
        if (d.gen != generation || tts == null) return;
        StringBuilder words = new StringBuilder(d.text);
        for (DeepReq n = d.next; n != null; n = n.next) {
            words.append(' ').append(n.text);
            deepReqs.remove(n.rid);
            n.f.delete();
        }
        Logs.d(ctx, "voice fx: the sound could not be made, reading it the ordinary way" + (d.why.isEmpty() ? "" : " (" + d.why + ")")
                + (d.voice.isEmpty() ? "" : ", voice " + d.voice) + ", " + words.length() + " letters");
        // (What was queued meanwhile may have given the first engine another voice.)
        if (d.voiceObj != null) {
            tts.setVoice(d.voiceObj);
            lastSet = d.voiceObj;
        }
        tts.setSpeechRate(d.rate * d.fx.pf);
        tts.setPitch(d.pitch * d.fx.pf);
        android.os.Bundle how = new android.os.Bundle();
        how.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, d.loud);
        tts.speak(words.toString(), TextToSpeech.QUEUE_ADD, how, d.id);
        releaseHeld(d.gen);
    }

    /** For the log: how loud the recording was, how much it was turned up or down, and how much the peaks had to be held in. */
    static String levels(float[] st) {
        if (st == null) return "";
        return String.format(java.util.Locale.US, ", sound in: %.2f average, %.2f peak; turned %s %.1fx; peaks held in on %.1f%% of it",
                st[0], st[1], st[2] >= 1f ? "up" : "down", st[2] >= 1f ? st[2] : 1f / Math.max(0.01f, st[2]), st[4] * 100f);
    }

    /** The changed sound is ready and its turn has come: play it, then let reading go on. */
    private void playDeep(DeepReq d) {
        final int gen = d.gen;
        try {
            short[] pcm = d.pcm;
            int sr = d.sr;
            d.pcm = null;
            final int frames = pcm.length;
            android.media.AudioTrack tr = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2).build();
            tr.write(pcm, 0, pcm.length);
            tr.setVolume(Math.min(1f, d.loud));
            if (d.first) { // (the next pieces of a long line are the same part going on)
                if (d.part == 0) runStarted(d.run, gen);
                partStarted(d.run, d.part, gen);
            }
            deepTracks.add(tr);
            tr.play();
            Logs.d(ctx, "voice fx: " + d.fx.name + ", " + Math.round(frames * 1000f / sr) + " ms, " + sr + " Hz, voice " + d.voice
                    + (d.pre ? " (recorded ahead)" : "") + levels(d.stats));
            // The track is let go once all of it has been heard, the room ringing on after the last word too.
            main.postDelayed(() -> {
                if (!deepTracks.remove(tr)) return; // stopped meanwhile, and let go of then
                try {
                    tr.stop();
                    tr.release();
                } catch (Exception ignored) {
                }
            }, frames * 1000L / sr + 300);
            final boolean last = d.last;
            final DeepReq next = d.next;
            // What comes after it begins where a speaker would go on, while the room's ring fades: the next piece of the line a
            // sentence's pause after its last word (so a cut sounds like any other full stop), the storyteller a moment after it.
            // (Waiting for the ring to end, and for the silence the voice puts after its words, made a long pause at each cut.)
            long spoken = Math.min(frames, Math.max(1, d.speechEnd)) * 1000L / sr;
            long goOn = next != null ? spoken + pauseAfter(d.endMark) - 30 // (the next piece has a breath of 30 ms before its first word)
                    : last ? frames * 1000L / sr + 250 // the end of what is read: all of it is heard first
                    : spoken + 200;
            deepFinishTask = () -> {
                if (gen != generation) return;
                if (next != null) { // the line goes on: its next piece, before anything else
                    nextPiece(next);
                    return;
                }
                releaseHeld(gen);
                if (last) allDone(gen);
            };
            main.postDelayed(deepFinishTask, Math.max(0, goOn));
        } catch (Throwable e) {
            Logs.d(ctx, "voice fx failed: " + e);
            d.f.delete();
            deepPlainly(d);
        }
    }

    /** What waited for God's words is given to the voice now, and the runs after it follow. */
    private void releaseHeld(int gen) {
        if (gen != generation) return;
        List<Runnable> h = new ArrayList<>(deepHeld);
        deepHeld.clear();
        deepHold = false;
        for (int x = 0; x < h.size(); x++) {
            if (deepHold) deepHeld.add(h.get(x)); // another of God's sayings in the same paragraph: the rest waits for it
            else h.get(x).run();
        }
        if (!deepHold && tts != null) {
            tts.setPitch(Prefs.pitch(ctx));
            List<TextPrep.Seg> runs = currentRuns;
            if (!translating) queueAhead(runs, gen, Math.max(0, curRun)); // a translation queues its own runs as they come
        }
    }

    private void stopDeep() {
        deepHold = false;
        deepHeld.clear();
        for (DeepReq d : deepReqs.values()) d.f.delete();
        deepReqs.clear();
        if (ttsSynth != null && synthReady) ttsSynth.stop();
        if (deepFinishTask != null) main.removeCallbacks(deepFinishTask);
        deepFinishTask = null;
        for (android.media.AudioTrack t : new ArrayList<>(deepTracks)) {
            try {
                t.stop();
                t.release();
            } catch (Exception ignored) {
            }
        }
        deepTracks.clear();
    }

    /** {sample rate, channels, where the sound starts, how long it is} of a WAV file the voice made; null if it is not one. */
    static int[] wavInfo(byte[] b) {
        if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return null;
        int pos = 12, sr = 0, ch = 0, bits = 16;
        while (pos + 8 <= b.length) {
            String tag = new String(b, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (b[pos + 4] & 255) | (b[pos + 5] & 255) << 8 | (b[pos + 6] & 255) << 16 | (b[pos + 7] & 255) << 24;
            if (tag.equals("fmt ")) {
                ch = (b[pos + 10] & 255) | (b[pos + 11] & 255) << 8;
                sr = (b[pos + 12] & 255) | (b[pos + 13] & 255) << 8 | (b[pos + 14] & 255) << 16 | (b[pos + 15] & 255) << 24;
                bits = (b[pos + 22] & 255) | (b[pos + 23] & 255) << 8;
            } else if (tag.equals("data")) {
                int off = pos + 8, rest = b.length - off;
                int len = size <= 0 || size > rest ? rest : size;
                if (sr <= 0 || ch < 1 || ch > 2 || bits != 16) return null;
                return new int[]{sr, ch, off, len & ~1};
            }
            if (size < 0) return null;
            pos += 8 + size + (size & 1);
        }
        return null;
    }

    private static final float QUESTION_PITCH = 1.12f;
    private boolean castLogged;
    private final java.util.Set<String> castLoggedFor = new java.util.HashSet<>(), noManLogged = new java.util.HashSet<>();


    /**
     * The run as {text, language, whose voice} pieces. Bible text inside the paragraph is a piece of its own
     * for the Bible voice, and in a story so is what each character says, for that character's voice; the
     * usual voice reads the rest.
     */
    /**
     * "Announce who is speaking": before each part where somebody else starts to speak, a part in the storyteller's voice
     * saying their name ("Ang Diyos.", "Jesus."). last[0] is the character named last (kept from run to run); the names said
     * are added to named. Returns where the first name went in, -1 for none.
     */
    static int nameSpeakers(List<String[]> parts, String[] last, String teller, List<String> named) {
        String voice = teller == null ? "" : teller.replace("~s", "");
        int first = -1;
        for (int k = 0; k < parts.size(); k++) {
            String code = Cast.voice(parts.get(k)[3]);
            if (!Cast.isCode(code) || code.equals(last[0])) continue;
            last[0] = code;
            String name = Cast.nameOf(code, parts.get(k)[1].equals(TextPrep.FIL));
            if (name == null) continue;
            parts.add(k, new String[]{TextPrep.say(name + ".", parts.get(k)[1]), parts.get(k)[1], "", voice});
            named.add(name);
            if (first < 0) first = k;
            k++;
        }
        return first;
    }

    static List<String[]> voiceParts(TextPrep.Seg s) {
        String t = s.text;
        String[] w = new String[t.length()];
        java.util.Arrays.fill(w, s.who);
        if (s.cast != null) {
            List<int[]> q = Cast.quoteSpans(t, s.inQuote);
            // A translation may bring back more or fewer quotation marks (castLost): then only a paragraph where one
            // character speaks keeps its voice.
            String only = null;
            boolean one = true;
            for (String c : s.cast) {
                if (c == null || c.isEmpty()) continue;
                if (only == null) only = c;
                else if (!only.equals(c)) one = false;
            }
            boolean off = s.castLost;
            for (int k = 0; k < q.size(); k++) {
                int idx = s.castFrom + k;
                String c = off ? (one && only != null && q.get(k)[1] - q.get(k)[0] > 12 ? only : "")
                        : idx < s.cast.length ? s.cast[idx] : "";
                if (c == null || c.isEmpty()) continue;
                if (c.startsWith("~")) c = s.who + c; // the storyteller's own words, whispered or shouted
                for (int x = q.get(k)[0]; x < q.get(k)[1]; x++) w[x] = c;
            }
        }
        boolean in = false;
        List<String[]> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String curWho = null;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            boolean mark = ch == Translate.VERSE_ON || ch == Translate.VERSE_OFF || ch == TextPrep.BIBLE_ON || ch == TextPrep.BIBLE_OFF;
            if (mark) in = ch == Translate.VERSE_ON || ch == TextPrep.BIBLE_ON;
            // Bible text is read in the Bible voice; God, Jesus, Satan (anyone given a voice) speaking in it keep theirs.
            String who = mark ? null : in && TextPrep.bibleVoice && !Cast.isCode(Cast.voice(w[i])) ? "m" : w[i];
            if (mark || !who.equals(curWho)) {
                addPart(out, cur.toString(), s, curWho);
                cur.setLength(0);
                curWho = who;
            }
            if (!mark) cur.append(ch);
        }
        addPart(out, cur.toString(), s, curWho);
        if (out.isEmpty()) out.add(new String[]{t, s.lang, s.who});
        return out;
    }

    private static void addPart(List<String[]> out, String piece, TextPrep.Seg s, String who) {
        // Quotation marks and punctuation left alone around a verse aren't worth a piece of their own.
        if (who == null || !piece.chars().anyMatch(Character::isLetterOrDigit)) return;
        // Two pieces for the same voice with only a mark between them are one.
        if (!out.isEmpty() && out.get(out.size() - 1)[2].equals(who)) {
            out.get(out.size() - 1)[0] += " " + piece.trim();
            return;
        }
        out.add(new String[]{piece.trim(), s.lang, who});
    }

    private static final long PARAGRAPH_PAUSE_MS = 200, HEADING_PAUSE_MS = 450;

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
    private Voice lastSet;

    private void applyVoice(String lang) {
        applyVoice(lang, "");
    }

    /**
     * Sets the voice for this language and reader, and returns how much higher or lower to say it (1: as it is).
     * A man's voice was asked for and the phone has none: the usual voice, lower. A story's characters take
     * the phone's other voices in turn, best first; when there are more characters than voices, a voice is used
     * again a little higher or lower.
     */
    private float applyVoice(String lang, String who) {
        who = Cast.voice(who);
        if (Cast.isCode(who)) return applyCast(lang, who);
        boolean man = "m".equals(who);
        Voice male = man ? secondVoice(lang) : null;
        if (male != null) {
            if (!male.equals(lastSet)) tts.setVoice(male);
            lastSet = male;
            return 1f;
        }
        applyUsual(lang);
        return man ? 0.82f : 1f;
    }

    private static final float[] AGAIN = {1f, 0.92f, 1.08f, 0.86f, 1.14f};
    private static final float[] NO_MAN = {0.80f, 0.72f, 0.88f, 0.76f}, NO_WOMAN = {1.14f, 1.24f, 1.07f, 1.19f}, NOT_SURE = {0.93f, 1.07f, 0.87f};

    private float applyCast(String lang, String who) {
        char g = Cast.genderOf(who);
        int n = Cast.slotOf(who);
        // God and Jesus: a man's voice, made deep.
        final boolean divine = g == 'd';
        if (divine) g = 'm';
        // Jesus given God the Father's voice speaks in the Father's phone voice too.
        final String as = divine ? divineAs(Cast.voice(who)) : Cast.voice(who);
        if (divine) n = Cast.slotOf(as);
        final float deep = divine ? depthFor(Cast.voice(who)) : 1f;
        // A character with an accent (British, Irish, Australian, Indian) is given a voice of that country, when the phone has one.
        final String accent = divine ? "" : Cast.accentOf(who);
        if (!accent.isEmpty() && !lang.equals(TextPrep.FIL)) {
            Voice av = accentVoice(lang, g, accent, who);
            if (av != null) {
                if (!av.equals(lastSet)) tts.setVoice(av);
                lastSet = av;
                return 1f;
            }
        }
        // The phone voice the user chose for this one in the voice lab (Apply).
        if (divine) {
            String want = VoiceFx.customVoice(divineStyle(lang, as));
            if (want == null) want = VoiceFx.defaultVoice(divineStyle(lang, as));
            if (want != null) {
                List<Voice> every = voicesFor(lang);
                for (Voice c : every) {
                    if (!base(c.getName()).equals(want)) continue;
                    Voice t = twin(c, every);
                    if (!t.equals(lastSet)) tts.setVoice(t);
                    lastSet = t;
                    return deep;
                }
            }
        }
        List<Voice> all = castPool(lang, g);
        // The best man's voice is kept for God and Jesus when there is another to give the rest.
        // The best men's voices are kept for the Father, the Son and the Spirit when there are others to give the rest.
        int reserve = !divine && g == 'm' ? (all.size() >= 4 ? 3 : all.size() >= 2 ? 1 : 0) : 0;
        List<Voice> pool = reserve > 0 ? all.subList(reserve, all.size()) : all;
        if (pool.isEmpty()) {
            applyUsual(lang);
            if (divine && plainJesus(Cast.voice(who))) return 1f; // the phone voice as it is, even with no man's voice
            return deep * (divine ? 0.9f : 1f) * (g == 'm' ? NO_MAN[n % NO_MAN.length] : g == 'f' ? NO_WOMAN[n % NO_WOMAN.length] : NOT_SURE[n % NOT_SURE.length]);
        }
        // A character keeps the voice they were first given for as long as ZYLENA runs, even when the list of
        // voices is reordered (a voice downloading, the connection coming and going).
        String pin = lang + "|" + as;
        Voice v = null;
        String had = castPinned.get(pin);
        if (had != null) for (Voice c : pool) if (base(c.getName()).equals(had)) v = c;
        if (v == null) {
            java.util.Set<String> taken = new java.util.HashSet<>();
            for (java.util.Map.Entry<String, String> e : castPinned.entrySet()) {
                if ((g == 'm' ? (e.getKey().startsWith(lang + "|cm") || e.getKey().startsWith(lang + "|cd")) : e.getKey().startsWith(lang + "|c" + g))
                        && !e.getKey().equals(pin)) taken.add(e.getValue());
            }
            if (divine && !all.isEmpty()) v = all.get(n % all.size()); // the Father the best, the Son the next, the Spirit the one after
            for (int k = 0; k < pool.size() && v == null; k++) {
                Voice c = pool.get((n + k) % pool.size());
                if (!taken.contains(base(c.getName()))) v = c;
            }
            if (v == null) v = pool.get(n % pool.size());
            castPinned.put(pin, base(v.getName()));
        }
        if (!v.equals(lastSet)) tts.setVoice(v);
        lastSet = v;
        return deep * (divine ? 1f : AGAIN[(n / pool.size()) % AGAIN.length]);
    }



    private final java.util.Set<String> accentMissing = new java.util.HashSet<>();

    /** A voice of the country (GB, IE, AU, IN) for this character, kept for as long as ZYLENA runs; null when the phone has none. */
    private Voice accentVoice(String lang, char g, String accent, String who) {
        String country = accent.toUpperCase(Locale.ROOT);
        String pin = lang + "|" + Cast.voice(who) + "@" + accent;
        List<Voice> all = voicesFor(lang);
        Voice found = null;
        String had = castPinned.get(pin);
        if (had != null) for (Voice c : all) if (base(c.getName()).equals(had)) found = c;
        if (found == null) {
            Voice best = null, bestAny = null;
            int bestScore = Integer.MIN_VALUE, bestAnyScore = Integer.MIN_VALUE;
            for (Voice v : all) {
                if (!v.getLocale().getCountry().equalsIgnoreCase(country)) continue;
                char vg = genderOf(v);
                if (vg == 'x') continue;
                int sc = v.getQuality();
                if (vg == g || vg == '?') {
                    if (vg == g) sc += 500;
                    if (sc > bestScore) {
                        bestScore = sc;
                        best = v;
                    }
                } else if (sc > bestAnyScore) {
                    bestAnyScore = sc;
                    bestAny = v;
                }
            }
            found = best != null ? twin(best, all) : null;
            if (found == null && bestAny != null) found = twin(bestAny, all);
            if (found == null) {
                if (accentMissing.add(lang + accent)) Logs.d(ctx, "no " + country + " voice on this phone for the " + accent + " accent: the usual voices are used");
                return null;
            }
            castPinned.put(pin, base(found.getName()));
        }
        return found;
    }

    private final java.util.Map<String, List<Voice>> castPools = new java.util.HashMap<>();
    /** Each character's voice ("en|cf1" → the voice's name), kept so a character never changes voice. */
    private final java.util.Map<String, String> castPinned = new java.util.HashMap<>();
    private long castPoolsAt;

    /** A voice's name without "-local" / "-network": the two are one voice, on the phone and online. */
    static String base(String name) {
        return name.endsWith("-local") ? name.substring(0, name.length() - 6)
                : name.endsWith("-network") ? name.substring(0, name.length() - 8) : name;
    }

    /** 'm' a man's voice, 'f' a woman's, 'x' not to be used for characters, '?' not known. The user's own word first. */
    char genderOf(Voice v) {
        String mine = Prefs.voiceGenders(ctx).get(base(v.getName()));
        if (mine != null && !mine.isEmpty()) return mine.charAt(0);
        String n = v.getName().toLowerCase(Locale.ROOT);
        if (n.contains("female")) return 'f';
        if (n.contains("male")) return 'm';
        java.util.regex.Matcher m = CODE.matcher(n);
        if (m.find()) {
            if (FEMALE_CODES.contains(m.group(1))) return 'f';
            if (MALE_CODES.contains(m.group(1))) return 'm';
        }
        return '?';
    }

    /** The voices a story's characters may use, best first: men's ('m'), women's ('f'); the reader's own voice is not among them. */
    List<Voice> castPool(String lang, char g) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - castPoolsAt > 15_000) {
            castPools.clear();
            castPoolsAt = now;
        }
        String key = lang + g;
        List<Voice> pool = castPools.get(key);
        if (pool != null) return pool;
        pool = new ArrayList<>();
        if (g == 'm' || g == 'f') {
            List<Voice> all = voicesFor(lang);
            Voice usual = chosenVoice(lang);
            String mine = usual == null ? "" : base(usual.getName());
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Voice v : all) {
                String b = base(v.getName());
                if (b.equals(mine) || genderOf(v) != g || !seen.add(b)) continue;
                pool.add(twin(v, all));
            }
            pool.sort((a, b) -> castScore(b, lang) - castScore(a, lang));
            // The Bible voice (the narrator of Bible text) is not also a character's, while other voices are left.
            Voice bible = secondVoice(lang);
            if (bible != null && pool.size() > 1) {
                String bb = base(bible.getName());
                pool.removeIf(v -> base(v.getName()).equals(bb));
            }
        }
        castPools.put(key, pool);
        return pool;
    }

    /** How good a voice is for a character: the right country first, then its quality. */
    private static int castScore(Voice v, String lang) {
        String c = v.getLocale().getCountry();
        int s = v.getQuality();
        if (lang.equals(TextPrep.FIL)) s += c.equals("PH") ? 1000 : 0;
        else s += c.equals("US") ? 1000 : c.equals("GB") ? 800 : c.equals("AU") ? 600 : c.equals("CA") || c.equals("IE") || c.equals("NZ") ? 500 : 0;
        return s;
    }

    /** For the log: which voices the characters get, "men: TPD, IOL · women: SFG, TPF". */
    String castVoices(String lang) {
        StringBuilder sb = new StringBuilder("men: ");
        for (Voice v : castPool(lang, 'm')) sb.append(base(v.getName())).append(' ');
        sb.append("· women: ");
        for (Voice v : castPool(lang, 'f')) sb.append(base(v.getName())).append(' ');
        return sb.toString().trim();
    }

    void voicesChanged() {
        castPools.clear();
        castPinned.clear();
    }

    /**
     * The second voice, for Bible verses and a story's other point of view: the one chosen in Settings; else
     * for Tagalog Google's "FIE" voice (the owner's pick), for English the best man's voice; null if none.
     */
    Voice secondVoice(String lang) {
        List<Voice> all = voicesFor(lang);
        Voice pick = null;
        String name = Prefs.voice2(ctx, lang);
        if (!name.isEmpty()) {
            for (Voice v : all) if (v.getName().equals(name)) pick = twin(v, all);
        }
        if (pick == null && lang.equals(TextPrep.FIL)) {
            for (Voice v : all) if (v.getName().toLowerCase(Locale.ROOT).contains("-x-fie")) pick = twin(v, all);
        }
        if (pick == null) pick = maleVoice(lang);
        Voice chosen = pick;
        // A woman tells the story: the verses are read by a man, so the two are never mixed up (the owner's wish), except that in
        // Tagalog FIE reads the Bible (the owner's choice; the Father, Jesus, the Spirit and Satan still have voices of their own).
        Voice usual = chosenVoice(lang);
        boolean fie = pick != null && lang.equals(TextPrep.FIL) && base(pick.getName()).toLowerCase(Locale.ROOT).contains("-x-fie");
        if (!fie && usual != null && genderOf(usual) == 'f' && (pick == null || genderOf(pick) != 'm')) {
            Voice man = maleVoice(lang);
            if (man == null || genderOf(man) != 'm') {
                man = null;
                for (Voice v : all) {
                    if (genderOf(v) != 'm') continue;
                    if (man == null || castScore(v, lang) > castScore(man, lang)) man = v;
                }
                man = man == null ? null : twin(man, all);
            }
            if (man != null) pick = man;
        }
        if (bibleVoiceLogged.add(lang)) {
            Logs.d(ctx, "Bible voice for " + lang + ": " + (pick == null ? "none (the storyteller's, lowered)" : base(pick.getName()) + " (" + genderOf(pick) + ")")
                    + ", storyteller " + (usual == null ? "none" : base(usual.getName()) + " (" + genderOf(usual) + ")")
                    + (chosen != pick ? ", changed from " + (chosen == null ? "none" : base(chosen.getName())) + " so a man reads the verses" : ""));
        }
        return pick;
    }

    /**
     * The phone voice the Father (cd0), Jesus (cd1) or the Holy Spirit (cd2) is said in when reading, without changing the
     * engine (for "Hear every voice"); null when the phone has none.
     */
    Voice divineVoice(String lang, String code) {
        if (tts == null || !ttsReady) return null;
        String as = divineAs(code);
        String want = VoiceFx.customVoice(divineStyle(lang, as));
        if (want == null) want = VoiceFx.defaultVoice(divineStyle(lang, as));
        List<Voice> every = voicesFor(lang);
        if (want != null) for (Voice c : every) if (base(c.getName()).equals(want)) return twin(c, every);
        List<Voice> all = castPool(lang, 'm');
        String had = castPinned.get(lang + "|" + as);
        if (had != null) for (Voice c : all) if (base(c.getName()).equals(had)) return c;
        if (!all.isEmpty()) return all.get(Cast.slotOf(as) % all.size());
        return chosenVoice(lang);
    }

    /** The kind of voice Jesus has now (his own, or God the Father's); null when he is read in the phone voice as it is. */
    String jesusStyle(String lang) {
        return plainJesus(Cast.JESUS_CODE) || VoiceFx.removed("jesus") ? null : divineStyle(lang, divineAs(Cast.JESUS_CODE));
    }

    /** How reading plays its sound: the voice lab's tests and "Hear every voice" play theirs the same way. */
    AudioAttributes audioAttributes() {
        return attrs;
    }

    /** The storyteller's voice for this language (the usual one), for "Hear every voice"'s drama; null when there is none. */
    Voice usualVoice(String lang) {
        return tts == null || !ttsReady ? null : chosenVoice(lang);
    }

    /** The voice as it is read now: its online twin when there is internet, else the one on the phone. */
    Voice asRead(Voice v) {
        return v == null || tts == null || !ttsReady ? v : twin(v, voicesFor(v.getLocale().getLanguage().equals("en") ? TextPrep.EN : TextPrep.FIL));
    }

    /** The best man's voice for the language on this phone, by the codes Google gives its voices; null if none. */
    private Voice maleVoice(String lang) {
        List<Voice> all = voicesFor(lang);
        Voice best = null;
        int bestScore = 0;
        for (Voice v : all) {
            String n = v.getName().toLowerCase(Locale.ROOT);
            int sc = n.contains("male") && !n.contains("female") ? 100 : 0;
            for (String code : MALE_CODES) if (n.contains("-x-" + code)) sc += 50;
            if (sc == 0) continue;
            sc += v.getQuality() / 100;
            String country = v.getLocale().getCountry();
            if (lang.equals(TextPrep.EN) && country.equals("US") || lang.equals(TextPrep.FIL) && country.equals("PH")) sc += 5;
            if (sc > bestScore) {
                bestScore = sc;
                best = v;
            }
        }
        return best == null ? null : twin(best, all);
    }

    private void applyUsual(String lang) {
        Voice v = chosenVoice(lang);
        if (v != null) {
            if (!v.equals(lastSet)) tts.setVoice(v); // the voice app loads a voice on each call: only when it changes
            lastSet = v;
        } else {
            lastSet = null;
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
        boolean on = Prefs.onlineVoice(ctx) && Net.online(ctx);
        String want = on && n.endsWith("-local") ? n.substring(0, n.length() - 6) + "-network"
                : !on && n.endsWith("-network") ? n.substring(0, n.length() - 8) + "-local" : null;
        if (want == null) return v;
        for (Voice o : all) if (o.getName().equals(want)) return o;
        return v;
    }

    private java.util.Set<Voice> voiceCache;
    private long voiceCacheAt;

    /**
     * The engine's voices. Asking for them is a call into the voice app that returns hundreds of voices;
     * done for every piece queued (twice), a long page held ZYLENA up for many seconds and Android closed
     * it ("isn't responding"). Kept for a minute, and asked afresh when the voice app changes.
     */
    private java.util.Set<Voice> allVoices() {
        long now = android.os.SystemClock.uptimeMillis();
        if (voiceCache == null || now - voiceCacheAt > 60_000) {
            java.util.Set<Voice> v = tts.getVoices();
            if (v != null) {
                voiceCache = v;
                voiceCacheAt = now;
            }
        }
        return voiceCache;
    }

    /** Installed voices for "en" or "fil", most likely female and best quality first. */
    /** A voice for this language that works without internet is on the phone. */
    boolean hasOfflineVoice(String lang) {
        for (Voice v : voicesFor(lang)) if (!v.isNetworkConnectionRequired()) return true;
        return false;
    }

    List<Voice> voicesFor(String lang) {
        List<Voice> out = new ArrayList<>();
        if (tts == null || !ttsReady) return out;
        java.util.Set<Voice> all = allVoices();
        if (all == null) return out;
        for (Voice v : all) {
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

    private static final java.util.List<String> FEMALE_CODES = java.util.Arrays.asList("tpf", "sfg", "tpc", "iog", "iob", "fie", "fic", "cfc",
            "gba", "gbc", "gbg", "fis", "aua", "auc", "afh", "ena", "enc", "ahp", "cxx", "tfn");
    private static final java.util.List<String> MALE_CODES = java.util.Arrays.asList("iol", "iom", "tpd", "fid", "fia", "cmh", "gbb", "gbd", "rjs", "aub", "aud", "end", "ene");
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
                tts.synthesizeToFile(TextPrep.withoutHalts(TextPrep.say(TextPrep.unmark(s.text), s.lang)), null, new java.io.File(saveDir, String.format(Locale.US, "%04d.wav", i)), id);
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
            } catch (Throwable e) { // out of memory too: that must not close ZYLENA
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
        // The second engine, which records God's, Jesus's and the characters' lines ahead, must be the new voice app too:
        // left on the old one, it was handed the new app's voices and said those lines in some other voice.
        if (ttsSynth != null) {
            try {
                ttsSynth.shutdown();
            } catch (Exception ignored) {
            }
        }
        ttsSynth = null;
        synthReady = false;
        voiceCache = null;
        lastSet = null;
        voicesChanged(); // the characters' voices were the old app's
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
