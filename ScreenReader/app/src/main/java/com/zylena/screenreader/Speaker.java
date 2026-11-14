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
        curRun = i;
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
        List<TextPrep.Seg> runs = paused ? pausedRuns : (speaking ? currentRuns : null);
        if (runs == null || runs.isEmpty()) return null;
        int at = paused ? pausedAt : curRun;
        TextPrep.Seg r = runs.get(Math.max(0, Math.min(at, runs.size() - 1)));
        StringBuilder sb = new StringBuilder();
        for (String[] part : voiceParts(r)) sb.append(sb.length() > 0 ? " " : "").append(TextPrep.sayName(part[0], part[1]));
        return sb.toString();
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
            Translate.text(ctx, all.toString(), s.lang, "", out -> {
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
        final int gen = ++generation;
        tracking = track;
        setSpeaking(true);
        Logs.d(ctx, "speak " + segs.size() + " pieces, track=" + track);
        // One run per screen line, with a short breath between paragraphs. A whole paragraph goes to the voice
        // in one piece where it can: the voice waits a moment before each piece, which sounds wrong mid-paragraph.
        List<TextPrep.Seg> runs = TextPrep.merge(segs, 350, 900, false, true);
        currentRuns = runs;
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
                            // A run spoken in several pieces (two voices) starts once, with its first piece.
                            if (p.length > 2 && p[2].startsWith("p") && !p[2].equals("p0")) return;
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
        final String source = all.toString();
        // A quoted verse with its reference after it: the Tagalog Bible's own words, not a new translation.
        String ref = runs.get(last).ref;
        if (ref != null && s.lang.equals(TextPrep.EN)) {
            String verse = Bible.quotedVerse(source, ref);
            if (verse != null) {
                p.out = verse.trim().replaceAll("[\\s,;:]+$", "") + ".";
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
                    runs.set(k, new TextPrep.Seg(part, to, o.line, o.lastLine, o.heading, true));
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
            long breath = head ? (online ? HEADING_PAUSE_MS / 3 : HEADING_PAUSE_MS) : (online ? 0 : PARAGRAPH_PAUSE_MS);
            if (breath > 0) tts.playSilentUtterance(breath, TextToSpeech.QUEUE_ADD, gen + ":-1");
        }
        // Voice and language switch between runs, so each run is queued as its own utterance; a Bible verse kept
        // in English inside a Tagalog run is its own utterance too, in the English voice.
        // A question is a piece of its own as well, said a little higher and slower, with a moment after it.
        List<String[]> parts = new ArrayList<>(); // {text, language, tone}
        boolean tone = Prefs.questionTone(ctx);
        for (String[] vp : voiceParts(s)) {
            String said = TextPrep.userFix(TextPrep.sayName(vp[0], vp[1]));
            if (!tone) parts.add(new String[]{said, vp[1], ""});
            else for (String[] t : TextPrep.tonePieces(said)) parts.add(new String[]{t[0], vp[1], t[1]});
        }
        float pitch = Prefs.pitch(ctx);
        for (int k = 0; k < parts.size(); k++) {
            String lang = parts.get(k)[1];
            boolean ask = parts.get(k)[2].equals("q");
            if (k > 0 || !lang.equals(s.lang)) applyVoice(lang);
            tts.setSpeechRate(rateFor(lang) * (ask ? 0.96f : 1f));
            tts.setPitch(pitch * (ask ? QUESTION_PITCH : 1f));
            String id = gen + ":" + i + (parts.size() > 1 ? ":p" + k : "") + (last && k == parts.size() - 1 ? ":last" : "");
            tts.speak(parts.get(k)[0], TextToSpeech.QUEUE_ADD, null, id);
            // Time to think about the question, unless the page ends here.
            if (ask && !(last && k == parts.size() - 1)) tts.playSilentUtterance(QUESTION_PAUSE_MS, TextToSpeech.QUEUE_ADD, gen + ":-1");
        }
        tts.setPitch(pitch);
    }

    private static final float QUESTION_PITCH = 1.12f;
    private static final long QUESTION_PAUSE_MS = 350;

    /** The run as {text, language} pieces: verses kept in English are pieces of their own. */
    private static List<String[]> voiceParts(TextPrep.Seg s) {
        List<String[]> out = new ArrayList<>();
        String t = s.text;
        int at = 0;
        while (at < t.length()) {
            int on = t.indexOf(Translate.VERSE_ON, at);
            if (on < 0) break;
            int off = t.indexOf(Translate.VERSE_OFF, on);
            if (off < 0) break;
            addPart(out, t.substring(at, on), s);
            String verse = t.substring(on + 1, off).trim();
            if (verse.chars().anyMatch(Character::isLetter)) out.add(new String[]{verse, TextPrep.EN});
            at = off + 1;
        }
        addPart(out, t.substring(at), s);
        if (out.isEmpty()) out.add(new String[]{spoken(s), s.lang});
        return out;
    }

    private static void addPart(List<String[]> out, String piece, TextPrep.Seg s) {
        // Quotation marks and punctuation left alone around a verse aren't worth a piece of their own.
        if (!piece.chars().anyMatch(Character::isLetterOrDigit)) return;
        TextPrep.Seg p = new TextPrep.Seg(piece.trim(), s.lang, s.line, s.lastLine, s.heading, s.translated);
        out.add(new String[]{spoken(p), s.lang});
    }

    /** A translation into Tagalog, respelled for the voice ("Herusalem", "edishon"). */
    private static String spoken(TextPrep.Seg s) {
        return s.translated && s.lang.equals(TextPrep.FIL) ? TextPrep.sayTagalog(s.text) : s.text;
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
                tts.synthesizeToFile(TextPrep.sayName(spoken(s).replace(String.valueOf(Translate.VERSE_ON), "").replace(String.valueOf(Translate.VERSE_OFF), ""), s.lang), null, new java.io.File(saveDir, String.format(Locale.US, "%04d.wav", i)), id);
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
