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

    private void runStarted(int i, int gen) {
        List<TextPrep.Seg> runs = currentRuns;
        if (gen != generation || i < 0 || i >= runs.size()) return;
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
        if (!r.sfx.isEmpty()) Sfx.play(ctx, r.sfx);
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
            Translate.text(ctx, all.toString().replace(String.valueOf(Translate.VERSE_ON), "").replace(String.valueOf(Translate.VERSE_OFF), ""), s.lang, "", out -> {
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
        final int gen = ++generation;
        tracking = track;
        setSpeaking(true);
        Logs.d(ctx, "speak " + segs.size() + " pieces, track=" + track);
        // One run per screen line, with a short breath between paragraphs. A whole paragraph goes to the voice
        // in one piece where it can: the voice waits a moment before each piece, which sounds wrong mid-paragraph.
        List<TextPrep.Seg> runs = TextPrep.merge(segs, 350, 900, false, true);
        currentRuns = runs;
        castLogged = false;
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
        TextPrep.Seg t = new TextPrep.Seg(r.text.substring(cut).trim(), r.lang, r.line, r.lastLine, r.heading, r.translated).like(r);
        t.sfx = "";
        if (r.cast != null) {
            int closed = 0;
            List<int[]> spans = Cast.quoteSpans(r.text.substring(0, cut), r.inQuote);
            for (int[] q : spans) closed += q[2];
            t.castFrom = r.castFrom + closed;
            t.inQuote = !spans.isEmpty() && spans.get(spans.size() - 1)[2] == 0;
        }
        Logs.d(ctx, "going on from the sentence being read (" + Math.round(f * 100) + "% of the paragraph said)");
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

    private final Runnable sleepForce = () -> {
        if (!sleepFinishing) return;
        sleepFinishing = false;
        Logs.d(ctx, "sleep timer: stopped reading");
        stop();
        toast("Sleep timer: reading stopped. Good night!");
    };

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
                        if (id != null && id.startsWith("deep:")) {
                            main.post(() -> deepStarted(id));
                            return;
                        }
                        try {
                            String[] p = id.split(":");
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
                            main.post(() -> deepMarker(id, false));
                            return;
                        }
                        if (id.startsWith("save:")) {
                            if (id.endsWith(":last")) saveFinished();
                            return;
                        }
                        if (id.endsWith(":last")) allDone(Integer.parseInt(id.split(":")[0]));
                    }

                    @Override
                    public void onError(String id) {
                        Logs.d(ctx, "TTS error on " + id);
                        if (id != null && id.startsWith("deep:")) {
                            main.post(() -> deepMarker(id, true));
                            return;
                        }
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
        if (gen != generation || runs != currentRuns || tts == null || deepHold) return;
        // Gemini's voice gets more at a time: it asks for several paragraphs in one request.
        int upTo = Math.min(runs.size() - 1, speaking + (GeminiVoice.usable(ctx) ? 8 : AHEAD));
        while (queuedTo < upTo && !deepHold) {
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
        final String source = all.toString().replace(String.valueOf(Translate.VERSE_ON), "").replace(String.valueOf(Translate.VERSE_OFF), "");
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

    /**
     * Queues run i in the asked-for language and goes on with the next. A few paragraphs are translated at
     * once, ahead of the voice, so it is only the very first one that is waited for. A paragraph that can't be
     * translated is read as it is.
     */
    private void translateNext(List<TextPrep.Seg> runs, int gen, int i) {
        if (gen != generation || i >= runs.size()) return;
        TextPrep.Seg s = runs.get(i);
        if (inTarget(s)) { // already in the language asked for
            queue(runs, gen, i);
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
                for (int k = i; k <= last; k++) {
                    String part = parts.get(k - i);
                    TextPrep.Seg o = runs.get(k);
                    // Kept in the usual spelling (the log shows it so); respelled for the voice only when spoken.
                    runs.set(k, new TextPrep.Seg(part, to, o.line, o.lastLine, o.heading, true).like(o));
                }
            } else if (i == 0) {
                toast("Couldn't translate. Reading as it is. The translation pack needs internet the first time.");
            }
            for (int k = i; k <= last; k++) queue(runs, gen, k);
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
            String text = s.text.replace(String.valueOf(Translate.VERSE_ON), "").replace(String.valueOf(Translate.VERSE_OFF), "");
            gemini().add(new GeminiVoice.Item(gen, i, TextPrep.sayName(text, s.lang), i == runs.size() - 1,
                    head ? HEADING_PAUSE_MS : newPara ? PARAGRAPH_PAUSE_MS : 0, rateFor(s.lang)));
            return;
        }
        queuePhone(runs, gen, i);
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
        if (i > 0 && runs.get(i - 1).lastLine != s.line) {
            // A short breath between paragraphs, a little longer around a heading. The online voice already
            // waits a moment before each piece, so it gets no extra breath between paragraphs.
            boolean head = s.heading || runs.get(i - 1).heading;
            boolean online = used != null && used.isNetworkConnectionRequired();
            // The pauses are the user's (Voice > Pauses); the online voice's own wait before each piece counts toward them.
            long want = (long) ((head ? Prefs.pauseHeading(ctx) : Prefs.pauseParagraph(ctx)) * 1000);
            long breath = online ? Math.max(0, want - 300) : want;
            if (breath > 0) tts.playSilentUtterance(breath, TextToSpeech.QUEUE_ADD, gen + ":-1");
        }
        // A scene change: a longer pause first, the way a narrator would take a breath before it.
        if (s.scene && i > 0) tts.playSilentUtterance(900, TextToSpeech.QUEUE_ADD, gen + ":-1");
        // Voice and language switch between runs, so each run is queued as its own utterance; a Bible verse kept
        // in English inside a Tagalog run is its own utterance too, in the English voice.
        // A question is a piece of its own as well, said a little higher and slower, with a moment after it.
        List<String[]> parts = new ArrayList<>(); // {text, language, tone}
        boolean tone = Prefs.questionTone(ctx);
        for (String[] vp : voiceParts(s)) {
            String said = TextPrep.say(vp[0], vp[1]);
            if (!tone) parts.add(new String[]{said, vp[1], "", vp[2]});
            else for (String[] t : TextPrep.tonePieces(said)) parts.add(new String[]{t[0], vp[1], t[1], vp[2]});
        }
        if (s.cast != null && parts.size() > 1) {
            StringBuilder v = new StringBuilder();
            for (String[] pt : parts) if (v.indexOf(pt[3].isEmpty() ? "usual" : pt[3]) < 0) v.append(v.length() > 0 ? ", " : "").append(pt[3].isEmpty() ? "usual" : pt[3]);
            if (v.indexOf(",") > 0 && !castLogged) {
                castLogged = true;
                Logs.d(ctx, "story voices for " + s.lang + ": " + castVoices(s.lang));
            }
            if (v.indexOf(",") > 0) Logs.d(ctx, "voices in run " + i + ": " + v);
        }
        long change = (long) (Prefs.num(ctx, "pause_voice", 0f) * 1000);
        float pitch = Prefs.pitch(ctx), strength = Prefs.toneStrength(ctx);
        // A heading is announced, and a paragraph that is one quotation (a memory verse) is read with care:
        // both a little lower and slower. They are whole runs already, so no extra pause comes of it.
        String whole = !tone ? "" : s.heading ? "h" : TextPrep.wholeQuote(s.text) ? "v" : "";
        final boolean dsp = !translating && !Prefs.testMode(ctx) && Prefs.flag(ctx, "voice_fx", true);
        float partsTotal = 1f, partsDone = 0f;
        {
            int tot = 0;
            for (String[] pt : parts) tot += pt[0].length();
            partsTotal = Math.max(1, tot);
        }
        for (int k = 0; k < parts.size(); k++) {
            String lang = parts.get(k)[1];
            String t = parts.get(k)[2].isEmpty() ? whole : parts.get(k)[2];
            boolean ask = t.equals("q");
            float p = ask ? QUESTION_PITCH : t.equals("e") ? 1.06f : t.equals("h") ? 0.97f : t.equals("v") ? 0.96f : 1f;
            boolean divineLine = Cast.voice(parts.get(k)[3]) != null && Cast.voice(parts.get(k)[3]).startsWith("cd");
            float r = ask ? 0.96f : t.equals("e") ? 1.02f : t.equals("h") ? 0.93f : t.equals("v") ? 0.94f : 1f;
            // How strong the tones are is the user's choice (Voice > Tone strength).
            p = 1f + (p - 1f) * strength;
            r = 1f + (r - 1f) * strength;
            // A character with a voice of their own kind (God's, a child's, an old person's, a husky one, a boss's, a maid's)
            // is made by processing the sound; see VoiceFx.
            final VoiceFx.Style fx = dsp ? styleFor(parts.get(k)[3]) : null;
            if (fx != null) r *= fx.tf / fx.pf; // said at this speed, it comes out at the tempo asked once the pitch is changed
            else if (divineLine) r *= 0.92f; // slower, with weight
            // A man's voice for Bible text and a man's point of view; with none on the phone, the usual voice lower.
            float vm = applyVoice(lang, parts.get(k)[3]);
            if (fx != null) p *= divineLine ? vm / DEEP : vm; // the pitch is changed afterwards
            else p *= vm;
            // The kind of text (suspense lower and slower, comedy livelier) and how the line is said (whispered, shouted).
            float[] kind = Mood.forKind(s.mood), feel = Mood.forFeeling(Cast.feel(parts.get(k)[3]));
            p *= (1f + (kind[0] - 1f) * strength) * (1f + (feel[0] - 1f) * strength);
            r *= (1f + (kind[1] - 1f) * strength) * (1f + (feel[1] - 1f) * strength);
            // A story is read a little under full loudness, so that a shout can be louder than the rest.
            float base = s.story && TextPrep.feelOn ? 0.8f : 1f;
            float loud = Math.max(0.3f, Math.min(1f, base * (1f + (kind[2] - 1f) * strength) * (1f + (feel[2] - 1f) * strength)));
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
            final int run = i, part = k;
            final String fl2 = lang;
            Runnable emit = () -> {
                if (gen != generation || tts == null) return;
                if (breath) tts.playSilentUtterance(change, TextToSpeech.QUEUE_ADD, gen + ":-1");
                applyVoice(fl2, who);
                tts.setSpeechRate(fr);
                tts.setPitch(fp);
                if (fx != null) synthDeep(say, id, fr, fp, fl * fx.vol, run, part, lastPart, gen, fx);
                else tts.speak(say, TextToSpeech.QUEUE_ADD, fhow, id);
                if (think) tts.playSilentUtterance(after, TextToSpeech.QUEUE_ADD, gen + ":-1");
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
     * recorded to a file by the voice, then played by ZYLENA slower, which makes them lower and slower at once
     * (the sound drops about three semitones): a voice with weight, whatever voice the phone uses.
     * What follows waits until they have been played.
     */
    private static final float DEEP = 0.78f;
    private volatile boolean deepHold;
    private final List<Runnable> deepHeld = new ArrayList<>();
    private android.media.AudioTrack deepTrack;
    private Runnable deepFinishTask;

    /** The kind of voice a character has, from the voice code: God's, or what the story says they are like; null for the plain voice. */
    private static VoiceFx.Style styleFor(String who) {
        String v = Cast.voice(who);
        if (v == null || !Cast.isCode(v)) return null;
        if (v.startsWith("cd")) return VoiceFx.get("deep");
        return VoiceFx.get(Cast.styleOf(who));
    }

    /** One line to be made into a character's voice: recorded by a second voice engine while the line before it is spoken. */
    private static final class DeepReq {
        java.io.File f;
        float loud, rate, pitch;
        int run, part, gen;
        boolean last, pre, recorded, failed, reached;
        String text, id;
        VoiceFx.Style fx;
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
        ttsSynth = new TextToSpeech(ctx, status -> main.post(() -> {
            if (status != TextToSpeech.SUCCESS || ttsSynth == null) {
                ttsSynth = null;
                return;
            }
            ttsSynth.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    main.post(() -> deepRecorded(id, false));
                }

                @Override
                public void onError(String id) {
                    main.post(() -> deepRecorded(id, true));
                }
            });
            synthReady = true;
            Logs.d(ctx, "voice fx: a second voice engine records the next lines ahead");
        }), pkg.isEmpty() ? null : pkg);
    }

    private void synthDeep(String text, String id, float rate, float pitch, float loud, int run, int part, boolean last, int gen, VoiceFx.Style fx) {
        final String rid = "deep:" + id;
        DeepReq d = new DeepReq();
        d.f = new java.io.File(ctx.getCacheDir(), "deep-" + id.replace(':', '_') + ".wav");
        d.loud = loud;
        d.rate = rate;
        d.pitch = pitch;
        d.run = run;
        d.part = part;
        d.gen = gen;
        d.last = last;
        d.text = text;
        d.id = id;
        d.fx = fx;
        deepReqs.put(rid, d);
        deepHold = true;
        if (!synthReady) initSynth();
        Voice v = null;
        try {
            v = tts.getVoice();
        } catch (Exception ignored) {
        }
        if (synthReady && ttsSynth != null && v != null) {
            // Recorded now by the second engine; the first engine's queue only says when this line's turn has come.
            d.pre = true;
            ttsSynth.setVoice(v);
            ttsSynth.setSpeechRate(rate);
            ttsSynth.setPitch(pitch);
            if (ttsSynth.synthesizeToFile(text, null, d.f, rid) != TextToSpeech.SUCCESS) d.failed = true;
            tts.playSilentUtterance(10, TextToSpeech.QUEUE_ADD, rid);
        } else {
            int rc = tts.synthesizeToFile(text, null, d.f, rid);
            if (rc != TextToSpeech.SUCCESS) {
                d.failed = true;
                d.reached = true;
                main.post(() -> deepTry(rid));
            }
        }
        // A second engine that never answers: when it is this line's turn, it is read the ordinary way.
        if (d.pre) {
            main.postDelayed(() -> {
                DeepReq x = deepReqs.get(rid);
                if (x == null || x.recorded) return;
                x.failed = true;
                deepTry(rid);
            }, 20000 + text.length() * 90L);
        }
    }

    /** The first engine began recording a line itself (no second engine): from now it may take only so long. */
    private void deepStarted(String rid) {
        DeepReq d = deepReqs.get(rid);
        if (d == null || d.pre) return;
        main.postDelayed(() -> {
            DeepReq x = deepReqs.get(rid);
            if (x == null) return;
            x.failed = !x.recorded;
            x.reached = true;
            deepTry(rid);
        }, 10000 + d.text.length() * 90L);
    }

    /** The recording of a line is done (or failed), by the second engine (pre) or by the first one. */
    private void deepRecorded(String rid, boolean error) {
        DeepReq d = deepReqs.get(rid);
        if (d == null) return;
        if (error) d.failed = true;
        else d.recorded = true;
        deepTry(rid);
    }

    /** The first engine got to this line's place in its queue (after everything before it was said). */
    private void deepMarker(String rid, boolean error) {
        DeepReq d = deepReqs.get(rid);
        if (d == null) return;
        d.reached = true;
        if (!d.pre) { // the first engine made the recording itself: this is its end
            if (error) d.failed = true;
            else d.recorded = true;
        }
        deepTry(rid);
    }

    /** Plays the line when its turn has come and it is recorded; reads it the ordinary way when the recording failed. */
    private void deepTry(String rid) {
        DeepReq d = deepReqs.get(rid);
        if (d == null || !d.reached) return;
        if (!d.failed && !d.recorded) return; // still being recorded: it plays as soon as it is
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

    /** The voice could not make the file: the words are read the ordinary way, lower, and reading goes on. */
    private void deepPlainly(DeepReq d) {
        if (d.gen != generation || tts == null) return;
        Logs.d(ctx, "voice fx: the sound could not be made, reading it the ordinary way");
        tts.setSpeechRate(d.rate * d.fx.pf);
        tts.setPitch(d.pitch * d.fx.pf);
        android.os.Bundle how = new android.os.Bundle();
        how.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, d.loud);
        tts.speak(d.text, TextToSpeech.QUEUE_ADD, how, d.id);
        releaseHeld(d.gen);
    }

    /** The recording is ready: change it into the character's voice, play it, then let reading go on. */
    private void playDeep(DeepReq d) {
        final int gen = d.gen;
        try {
            byte[] b = new byte[(int) d.f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(d.f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            d.f.delete();
            int[] w = wavInfo(b);
            if (w == null || w[3] < 400) throw new java.io.IOException("not a usable recording");
            int sr = w[0], ch = w[1];
            short[] pcm = new short[w[3] / 2];
            java.nio.ByteBuffer.wrap(b, w[2], w[3]).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            if (ch == 2) { // mono
                short[] mono = new short[pcm.length / 2];
                for (int i = 0; i < mono.length; i++) mono[i] = (short) ((pcm[2 * i] + pcm[2 * i + 1]) / 2);
                pcm = mono;
            }
            pcm = VoiceFx.process(pcm, sr, d.fx);
            final int frames = pcm.length;
            android.media.AudioTrack tr = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2).build();
            tr.write(pcm, 0, pcm.length);
            tr.setVolume(Math.min(1f, d.loud));
            if (d.part == 0) runStarted(d.run, gen);
            deepTrack = tr;
            tr.play();
            Logs.d(ctx, "voice fx: " + d.fx.name + ", " + Math.round(frames * 1000f / sr) + " ms" + (d.pre ? " (recorded ahead)" : ""));
            final boolean last = d.last;
            deepFinishTask = () -> {
                try {
                    tr.stop();
                    tr.release();
                } catch (Exception ignored) {
                }
                if (deepTrack == tr) deepTrack = null;
                if (gen != generation) return;
                releaseHeld(gen);
                if (last) allDone(gen);
            };
            main.postDelayed(deepFinishTask, frames * 1000L / sr + 250);
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
            queueAhead(runs, gen, Math.max(0, curRun));
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
        android.media.AudioTrack t = deepTrack;
        deepTrack = null;
        if (t != null) {
            try {
                t.stop();
                t.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** {sample rate, channels, where the sound starts, how long it is} of a WAV file the voice made; null if it is not one. */
    private static int[] wavInfo(byte[] b) {
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


    /**
     * The run as {text, language, whose voice} pieces. Bible text inside the paragraph is a piece of its own
     * for the Bible voice, and in a story so is what each character says, for that character's voice; the
     * usual voice reads the rest.
     */
    static List<String[]> voiceParts(TextPrep.Seg s) {
        String t = s.text;
        String[] w = new String[t.length()];
        java.util.Arrays.fill(w, s.who);
        if (s.cast != null) {
            List<int[]> q = Cast.quoteSpans(t, s.inQuote);
            // A translation may bring back more or fewer quotation marks: then only a paragraph where one
            // character speaks keeps its voice.
            boolean whole = s.castFrom == 0 && !s.inQuote;
            String only = null;
            boolean one = true;
            for (String c : s.cast) {
                if (c == null || c.isEmpty()) continue;
                if (only == null) only = c;
                else if (!only.equals(c)) one = false;
            }
            boolean off = s.translated && whole && q.size() != s.cast.length;
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
            boolean mark = ch == Translate.VERSE_ON || ch == Translate.VERSE_OFF;
            if (mark) in = ch == Translate.VERSE_ON;
            String who = mark ? null : in && TextPrep.bibleVoice ? "m" : w[i];
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
        final float deep = divine ? DEEP : 1f;
        List<Voice> pool = castPool(lang, g);
        if (pool.isEmpty()) {
            applyUsual(lang);
            return deep * (divine ? 0.9f : 1f) * (g == 'm' ? NO_MAN[n % NO_MAN.length] : g == 'f' ? NO_WOMAN[n % NO_WOMAN.length] : NOT_SURE[n % NOT_SURE.length]);
        }
        // A character keeps the voice they were first given for as long as ZYLENA runs, even when the list of
        // voices is reordered (a voice downloading, the connection coming and going).
        String pin = lang + "|" + Cast.voice(who);
        Voice v = null;
        String had = castPinned.get(pin);
        if (had != null) for (Voice c : pool) if (base(c.getName()).equals(had)) v = c;
        if (v == null) {
            java.util.Set<String> taken = new java.util.HashSet<>();
            for (java.util.Map.Entry<String, String> e : castPinned.entrySet()) {
                if ((g == 'm' ? (e.getKey().startsWith(lang + "|cm") || e.getKey().startsWith(lang + "|cd")) : e.getKey().startsWith(lang + "|c" + g))
                        && !e.getKey().equals(pin)) taken.add(e.getValue());
            }
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
        String name = Prefs.voice2(ctx, lang);
        if (!name.isEmpty()) {
            for (Voice v : all) if (v.getName().equals(name)) return twin(v, all);
        }
        if (lang.equals(TextPrep.FIL)) {
            for (Voice v : all) if (v.getName().toLowerCase(Locale.ROOT).contains("-x-fie")) return twin(v, all);
        }
        return maleVoice(lang);
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
                tts.synthesizeToFile(TextPrep.say(s.text.replace(String.valueOf(Translate.VERSE_ON), "").replace(String.valueOf(Translate.VERSE_OFF), ""), s.lang), null, new java.io.File(saveDir, String.format(Locale.US, "%04d.wav", i)), id);
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
        voiceCache = null;
        lastSet = null;
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
